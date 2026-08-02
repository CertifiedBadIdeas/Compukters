use super::packet::{
    decode_packet, DecodedDrawCommandKind, DecodedDrawList, DecodedOperation, DecodedOperationKind,
    PacketDecodeError,
};
use super::{
    DestinationRect, DrawCommand, DrawList, ImageRgb565, Mask1Bpp, MaskInstance,
    MaskInstanceBuffer, MaskInstanceRecord, Resource, ResourceEntry, ResourceRef, ResultCode,
    SourceRect, MAX_CLIP_DEPTH, MAX_RESOURCES, MAX_RESOURCE_BYTES, MAX_TOTAL_RESOURCE_BYTES,
};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct GuestRejection {
    pub code: ResultCode,
    pub operation_index: u32,
    pub byte_offset: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SubmissionOutcome {
    Committed { sequence: u64 },
    Rejected(GuestRejection),
}

#[derive(Debug, thiserror::Error)]
pub enum RetainedGpuFault {
    #[error("retained GPU allocation failed")]
    Allocation,
    #[error("retained GPU monotonic counter exhausted")]
    CounterExhausted,
    #[error("retained GPU authoritative state is corrupt")]
    CorruptState,
}

pub struct RetainedGpu {
    commit_sequence: u64,
    next_incarnation: u64,
    resources: Vec<ResourceEntry>,
    draw_list: DrawList,
}

impl RetainedGpu {
    pub fn try_new() -> Result<Self, RetainedGpuFault> {
        let mut resources = Vec::new();
        resources
            .try_reserve_exact(MAX_RESOURCES)
            .map_err(|_| RetainedGpuFault::Allocation)?;
        Ok(Self {
            commit_sequence: 0,
            next_incarnation: 1,
            resources,
            draw_list: DrawList::from_validated_parts(0, Vec::new(), 8),
        })
    }

    pub fn submit(&mut self, packet: &[u8]) -> Result<SubmissionOutcome, RetainedGpuFault> {
        let decoded = match decode_packet(packet) {
            Ok(packet) => packet,
            Err(PacketDecodeError::Rejected(rejection)) => {
                return Ok(SubmissionOutcome::Rejected(GuestRejection {
                    code: rejection.code,
                    operation_index: rejection.operation_index,
                    byte_offset: rejection.byte_offset,
                }));
            }
            Err(PacketDecodeError::Allocation) => return Err(RetainedGpuFault::Allocation),
        };
        if decoded.expected_base_sequence != self.commit_sequence {
            return Ok(reject(ResultCode::StaleBase, u32::MAX, 16));
        }
        let next_sequence = self
            .commit_sequence
            .checked_add(1)
            .ok_or(RetainedGpuFault::CounterExhausted)?;
        let prepared = match PreparedTransaction::build(self, &decoded.operations)? {
            Ok(prepared) => prepared,
            Err(rejection) => return Ok(SubmissionOutcome::Rejected(rejection)),
        };
        self.commit(prepared)?;
        self.commit_sequence = next_sequence;
        Ok(SubmissionOutcome::Committed {
            sequence: next_sequence,
        })
    }

    pub fn commit_sequence(&self) -> u64 {
        self.commit_sequence
    }

    pub fn resources(&self) -> &[ResourceEntry] {
        &self.resources
    }

    pub fn draw_list(&self) -> &DrawList {
        &self.draw_list
    }

    pub fn authoritative_payload_bytes(&self) -> usize {
        self.resources
            .iter()
            .map(|entry| entry.value.payload_bytes())
            .sum::<usize>()
            + self.draw_list.encoded_byte_len()
    }

    fn commit(&mut self, prepared: PreparedTransaction<'_>) -> Result<(), RetainedGpuFault> {
        for action in prepared.actions {
            match action {
                PreparedAction::Create { entry, .. } => {
                    let index = self
                        .resources
                        .binary_search_by_key(&entry.id, |resource| resource.id)
                        .map_or_else(Ok, |_| Err(RetainedGpuFault::CorruptState))?;
                    if self.resources.len() == self.resources.capacity() {
                        return Err(RetainedGpuFault::CorruptState);
                    }
                    self.resources.insert(index, entry);
                }
                PreparedAction::PatchImage {
                    resource_id,
                    x,
                    y,
                    width,
                    height,
                    pixels,
                    ..
                } => {
                    let entry = find_resource_mut(&mut self.resources, resource_id)?;
                    let Resource::ImageRgb565(image) = &mut entry.value else {
                        return Err(RetainedGpuFault::CorruptState);
                    };
                    image
                        .patch_rect(x, y, width, height, &pixels)
                        .map_err(|_| RetainedGpuFault::CorruptState)?;
                    entry.revision = entry
                        .revision
                        .checked_add(1)
                        .ok_or(RetainedGpuFault::CounterExhausted)?;
                }
                PreparedAction::PatchMask {
                    resource_id,
                    x,
                    y,
                    width,
                    height,
                    rows,
                    ..
                } => {
                    let entry = find_resource_mut(&mut self.resources, resource_id)?;
                    let Resource::Mask1Bpp(mask) = &mut entry.value else {
                        return Err(RetainedGpuFault::CorruptState);
                    };
                    mask.patch_rect(x, y, width, height, rows)
                        .map_err(|_| RetainedGpuFault::CorruptState)?;
                    entry.revision = entry
                        .revision
                        .checked_add(1)
                        .ok_or(RetainedGpuFault::CounterExhausted)?;
                }
                PreparedAction::PatchInstances {
                    resource_id,
                    start_index,
                    instances,
                    ..
                } => {
                    let entry = find_resource_mut(&mut self.resources, resource_id)?;
                    let Resource::MaskInstanceBuffer(buffer) = &mut entry.value else {
                        return Err(RetainedGpuFault::CorruptState);
                    };
                    buffer
                        .patch(start_index, &instances)
                        .map_err(|_| RetainedGpuFault::CorruptState)?;
                    entry.revision = entry
                        .revision
                        .checked_add(1)
                        .ok_or(RetainedGpuFault::CounterExhausted)?;
                }
                PreparedAction::Drop { resource_id, .. } => {
                    let index = self
                        .resources
                        .binary_search_by_key(&resource_id, |entry| entry.id)
                        .map_err(|_| RetainedGpuFault::CorruptState)?;
                    self.resources.remove(index);
                }
            }
        }
        if let Some(draw_list) = prepared.draw_list {
            self.draw_list = draw_list;
        }
        self.next_incarnation = prepared.next_incarnation;
        Ok(())
    }
}

struct PreparedTransaction<'a> {
    actions: Vec<PreparedAction<'a>>,
    draw_list: Option<DrawList>,
    next_incarnation: u64,
}

enum PreparedAction<'a> {
    Create {
        entry: ResourceEntry,
    },
    PatchImage {
        resource_id: u32,
        x: u16,
        y: u16,
        width: u16,
        height: u16,
        pixels: Vec<u16>,
    },
    PatchMask {
        resource_id: u32,
        x: u16,
        y: u16,
        width: u16,
        height: u16,
        rows: &'a [u8],
    },
    PatchInstances {
        resource_id: u32,
        start_index: u16,
        instances: Vec<MaskInstance>,
    },
    Drop {
        resource_id: u32,
    },
}

#[derive(Clone, Copy)]
enum StagedSource {
    Existing(usize),
    Created(usize),
}

struct StagedResource {
    id: u32,
    incarnation: u64,
    revision: u64,
    source: StagedSource,
    live: bool,
    drop_operation: Option<usize>,
}

#[derive(Clone, Copy)]
struct InstanceOverride {
    resource_id: u32,
    instance_index: usize,
    operation_index: usize,
    byte_offset: u32,
    value: MaskInstance,
}

impl<'a> PreparedTransaction<'a> {
    fn build(
        gpu: &RetainedGpu,
        operations: &[DecodedOperation<'a>],
    ) -> Result<Result<Self, GuestRejection>, RetainedGpuFault> {
        let mut actions = Vec::new();
        actions
            .try_reserve_exact(operations.len())
            .map_err(|_| RetainedGpuFault::Allocation)?;
        let mut staged = Vec::new();
        staged
            .try_reserve_exact(MAX_RESOURCES * 2)
            .map_err(|_| RetainedGpuFault::Allocation)?;
        let override_capacity: usize = operations
            .iter()
            .filter_map(|operation| match &operation.kind {
                DecodedOperationKind::PatchMaskInstances { records, .. } => {
                    Some(records.len() / 24)
                }
                _ => None,
            })
            .sum();
        let mut instance_overrides = Vec::new();
        instance_overrides
            .try_reserve_exact(override_capacity)
            .map_err(|_| RetainedGpuFault::Allocation)?;
        for (index, entry) in gpu.resources.iter().enumerate() {
            staged.push(StagedResource {
                id: entry.id,
                incarnation: entry.incarnation,
                revision: entry.revision,
                source: StagedSource::Existing(index),
                live: true,
                drop_operation: None,
            });
        }
        let mut next_incarnation = gpu.next_incarnation;
        let mut total_payload = gpu
            .resources
            .iter()
            .map(|entry| entry.value.payload_bytes())
            .sum::<usize>();
        let mut draw_list = None;

        for (operation_index, operation) in operations.iter().enumerate() {
            let patched_resource_id = match &operation.kind {
                DecodedOperationKind::PatchImageRect { resource_id, .. }
                | DecodedOperationKind::PatchMaskRect { resource_id, .. }
                | DecodedOperationKind::PatchMaskInstances { resource_id, .. } => {
                    Some(*resource_id)
                }
                _ => None,
            };
            if patched_resource_id.is_some_and(|resource_id| {
                staged.iter().any(|entry| {
                    entry.id == resource_id && entry.live && entry.revision == u64::MAX
                })
            }) && patch_semantics_are_valid(gpu, &staged, &actions, operation)
            {
                return Err(RetainedGpuFault::CounterExhausted);
            }
            let result = match &operation.kind {
                DecodedOperationKind::CreateImageRgb565 {
                    resource_id,
                    width,
                    height,
                    pixels,
                } => {
                    if let Err(rejection) = precheck_create(
                        &staged,
                        operation,
                        operation_index,
                        *resource_id,
                        pixels.len(),
                        total_payload,
                    ) {
                        return Ok(Err(rejection));
                    }
                    if next_incarnation == u64::MAX {
                        return Err(RetainedGpuFault::CounterExhausted);
                    }
                    let values = decode_pixels(pixels)?;
                    let resource = match ImageRgb565::new(*width, *height, values) {
                        Ok(image) => Resource::ImageRgb565(image),
                        Err(_) => {
                            return Ok(Err(operation_rejection_at(
                                operation,
                                operation_index,
                                ResultCode::InvalidArgument,
                                if *width == 0 { 12 } else { 14 },
                            )))
                        }
                    };
                    stage_create(
                        &mut staged,
                        &mut actions,
                        operation_index,
                        operation,
                        *resource_id,
                        resource,
                        &mut next_incarnation,
                        &mut total_payload,
                    )
                }
                DecodedOperationKind::CreateMask1Bpp {
                    resource_id,
                    width,
                    height,
                    rows,
                } => {
                    if let Err(rejection) = precheck_create(
                        &staged,
                        operation,
                        operation_index,
                        *resource_id,
                        rows.len(),
                        total_payload,
                    ) {
                        return Ok(Err(rejection));
                    }
                    if next_incarnation == u64::MAX {
                        return Err(RetainedGpuFault::CounterExhausted);
                    }
                    let copied = try_copy(rows)?;
                    let resource = match Mask1Bpp::new(*width, *height, copied) {
                        Ok(mask) => Resource::Mask1Bpp(mask),
                        Err(_) => {
                            return Ok(Err(operation_rejection_at(
                                operation,
                                operation_index,
                                ResultCode::InvalidArgument,
                                if *width == 0 { 12 } else { 14 },
                            )))
                        }
                    };
                    stage_create(
                        &mut staged,
                        &mut actions,
                        operation_index,
                        operation,
                        *resource_id,
                        resource,
                        &mut next_incarnation,
                        &mut total_payload,
                    )
                }
                DecodedOperationKind::CreateMaskInstanceBuffer {
                    resource_id,
                    capacity,
                    records,
                } => {
                    if let Err(rejection) = precheck_create(
                        &staged,
                        operation,
                        operation_index,
                        *resource_id,
                        records.len(),
                        total_payload,
                    ) {
                        return Ok(Err(rejection));
                    }
                    if next_incarnation == u64::MAX {
                        return Err(RetainedGpuFault::CounterExhausted);
                    }
                    let values = decode_instances(records)?;
                    let resource = match MaskInstanceBuffer::new(*capacity, values) {
                        Ok(buffer) => Resource::MaskInstanceBuffer(buffer),
                        Err(_) => {
                            return Ok(Err(operation_rejection_at(
                                operation,
                                operation_index,
                                ResultCode::InvalidArgument,
                                12,
                            )))
                        }
                    };
                    stage_create(
                        &mut staged,
                        &mut actions,
                        operation_index,
                        operation,
                        *resource_id,
                        resource,
                        &mut next_incarnation,
                        &mut total_payload,
                    )
                }
                DecodedOperationKind::PatchImageRect {
                    resource_id,
                    x,
                    y,
                    width,
                    height,
                    pixels,
                } => {
                    let values = decode_pixels(pixels)?;
                    stage_patch_image(
                        gpu,
                        &mut staged,
                        &mut actions,
                        operation_index,
                        operation,
                        *resource_id,
                        *x,
                        *y,
                        *width,
                        *height,
                        values,
                    )
                }
                DecodedOperationKind::PatchMaskRect {
                    resource_id,
                    x,
                    y,
                    width,
                    height,
                    rows,
                } => stage_patch_mask(
                    gpu,
                    &mut staged,
                    &mut actions,
                    operation_index,
                    operation,
                    *resource_id,
                    *x,
                    *y,
                    *width,
                    *height,
                    rows,
                ),
                DecodedOperationKind::PatchMaskInstances {
                    resource_id,
                    start_index,
                    records,
                    ..
                } => {
                    let values = decode_instances(records)?;
                    stage_patch_instances(
                        gpu,
                        &mut staged,
                        &mut actions,
                        &mut instance_overrides,
                        operation_index,
                        operation,
                        *resource_id,
                        *start_index,
                        values,
                    )
                }
                DecodedOperationKind::DropResource { resource_id } => stage_drop(
                    gpu,
                    &mut staged,
                    &mut actions,
                    operation_index,
                    operation,
                    *resource_id,
                    &mut total_payload,
                ),
                DecodedOperationKind::ReplaceDrawList { draw_list: decoded } => {
                    sort_instance_overrides(&mut instance_overrides);
                    match resolve_draw_list(gpu, &staged, &actions, &instance_overrides, decoded)? {
                        Ok(resolved) => {
                            draw_list = Some(resolved);
                            Ok(())
                        }
                        Err(error) => Err(GuestRejection {
                            code: error.code,
                            operation_index: operation_index as u32,
                            byte_offset: error.byte_offset,
                        }),
                    }
                }
            };
            if let Err(rejection) = result {
                return Ok(Err(rejection));
            }
        }

        sort_instance_overrides(&mut instance_overrides);

        for entry in staged.iter().filter(|entry| !entry.live) {
            let reference = ResourceRef {
                id: entry.id,
                incarnation: entry.incarnation,
            };
            if draw_list
                .as_ref()
                .unwrap_or(&gpu.draw_list)
                .references(reference)
            {
                let operation_index = entry.drop_operation.expect("dropped resource operation");
                return Ok(Err(operation_rejection(
                    &operations[operation_index],
                    operation_index,
                    ResultCode::ResourceInUse,
                )));
            }
        }
        if draw_list.is_none() {
            if let Some((operation_index, byte_offset)) = invalidating_patch_operation(
                gpu,
                &staged,
                &actions,
                &instance_overrides,
                &gpu.draw_list,
            ) {
                return Ok(Err(GuestRejection {
                    code: ResultCode::InvalidDrawList,
                    operation_index: operation_index as u32,
                    byte_offset,
                }));
            }
        }
        Ok(Ok(Self {
            actions,
            draw_list,
            next_incarnation,
        }))
    }
}

fn invalidating_patch_operation(
    gpu: &RetainedGpu,
    staged: &[StagedResource],
    actions: &[PreparedAction<'_>],
    instance_overrides: &[InstanceOverride],
    draw_list: &DrawList,
) -> Option<(usize, u32)> {
    for command in draw_list.commands() {
        let DrawCommand::DrawMaskInstances {
            mask,
            instances,
            first_instance,
            instance_count,
            ..
        } = command
        else {
            continue;
        };
        let Some((resolved_mask, mask_resource)) = staged_resource(gpu, staged, actions, mask.id)
        else {
            continue;
        };
        let Some((resolved_instances, instances_resource)) =
            staged_resource(gpu, staged, actions, instances.id)
        else {
            continue;
        };
        if resolved_mask != *mask || resolved_instances != *instances {
            continue;
        }
        let Resource::Mask1Bpp(mask_resource) = mask_resource else {
            continue;
        };
        let Resource::MaskInstanceBuffer(instance_buffer) = instances_resource else {
            continue;
        };
        let start = usize::from(*first_instance);
        let end = start + usize::from(*instance_count);
        if end > instance_buffer.instances().len() {
            continue;
        }
        for index in start..end {
            let (instance, contributor) = final_instance(
                instance_overrides,
                instances.id,
                instance_buffer.instances()[index],
                index,
            );
            let record = instance.record();
            let invalid = u32::from(record.source_x) + u32::from(record.source_width)
                > u32::from(mask_resource.width())
                || u32::from(record.source_y) + u32::from(record.source_height)
                    > u32::from(mask_resource.height());
            if invalid {
                let contributor = contributor
                    .expect("an installed valid draw list can only be invalidated by an override");
                let relative_offset = invalid_instance_field_offset(
                    record,
                    mask_resource.width(),
                    mask_resource.height(),
                );
                return Some((
                    contributor.operation_index,
                    contributor.byte_offset + relative_offset,
                ));
            }
        }
    }
    None
}

fn stage_create(
    staged: &mut Vec<StagedResource>,
    actions: &mut Vec<PreparedAction<'_>>,
    operation_index: usize,
    operation: &DecodedOperation<'_>,
    resource_id: u32,
    resource: Resource,
    next_incarnation: &mut u64,
    total_payload: &mut usize,
) -> Result<(), GuestRejection> {
    if resource_id == 0 {
        return Err(operation_rejection(
            operation,
            operation_index,
            ResultCode::InvalidArgument,
        ));
    }
    if staged.iter().any(|entry| entry.id == resource_id) {
        return Err(operation_rejection(
            operation,
            operation_index,
            ResultCode::InvalidResource,
        ));
    }
    let payload = resource.payload_bytes();
    if staged.iter().filter(|entry| entry.live).count() == MAX_RESOURCES {
        return Err(operation_rejection(
            operation,
            operation_index,
            ResultCode::QuotaExceeded,
        ));
    }
    let quota_offset = create_quota_field_offset(operation);
    if payload > MAX_RESOURCE_BYTES {
        return Err(operation_rejection_at(
            operation,
            operation_index,
            ResultCode::QuotaExceeded,
            quota_offset,
        ));
    }
    let next_total = total_payload.checked_add(payload).ok_or_else(|| {
        operation_rejection_at(
            operation,
            operation_index,
            ResultCode::QuotaExceeded,
            quota_offset,
        )
    })?;
    if next_total > MAX_TOTAL_RESOURCE_BYTES {
        return Err(operation_rejection_at(
            operation,
            operation_index,
            ResultCode::QuotaExceeded,
            quota_offset,
        ));
    }
    let incarnation = *next_incarnation;
    *next_incarnation = next_incarnation
        .checked_add(1)
        .expect("counter exhaustion checked before resource allocation");
    let created_index = actions.len();
    actions.push(PreparedAction::Create {
        entry: ResourceEntry {
            id: resource_id,
            incarnation,
            revision: 1,
            value: resource,
        },
    });
    staged.push(StagedResource {
        id: resource_id,
        incarnation,
        revision: 1,
        source: StagedSource::Created(created_index),
        live: true,
        drop_operation: None,
    });
    *total_payload = next_total;
    Ok(())
}

fn precheck_create(
    staged: &[StagedResource],
    operation: &DecodedOperation<'_>,
    operation_index: usize,
    resource_id: u32,
    payload_bytes: usize,
    total_payload: usize,
) -> Result<(), GuestRejection> {
    if let Some(relative_offset) = create_shape_error_offset(operation) {
        return Err(operation_rejection_at(
            operation,
            operation_index,
            ResultCode::InvalidArgument,
            relative_offset,
        ));
    }
    if resource_id == 0 {
        return Err(operation_rejection(
            operation,
            operation_index,
            ResultCode::InvalidArgument,
        ));
    }
    if staged.iter().any(|entry| entry.id == resource_id) {
        return Err(operation_rejection(
            operation,
            operation_index,
            ResultCode::InvalidResource,
        ));
    }
    if staged.iter().filter(|entry| entry.live).count() == MAX_RESOURCES {
        return Err(operation_rejection(
            operation,
            operation_index,
            ResultCode::QuotaExceeded,
        ));
    }
    if payload_bytes > MAX_RESOURCE_BYTES
        || total_payload
            .checked_add(payload_bytes)
            .is_none_or(|total| total > MAX_TOTAL_RESOURCE_BYTES)
    {
        return Err(operation_rejection_at(
            operation,
            operation_index,
            ResultCode::QuotaExceeded,
            create_quota_field_offset(operation),
        ));
    }
    Ok(())
}

fn create_shape_error_offset(operation: &DecodedOperation<'_>) -> Option<u32> {
    match &operation.kind {
        DecodedOperationKind::CreateImageRgb565 { width, height, .. }
        | DecodedOperationKind::CreateMask1Bpp { width, height, .. } => {
            if *width == 0 {
                Some(12)
            } else if *height == 0 {
                Some(14)
            } else {
                None
            }
        }
        DecodedOperationKind::CreateMaskInstanceBuffer { capacity: 0, .. } => Some(12),
        _ => None,
    }
}

fn create_quota_field_offset(operation: &DecodedOperation<'_>) -> u32 {
    match &operation.kind {
        DecodedOperationKind::CreateImageRgb565 { .. }
        | DecodedOperationKind::CreateMask1Bpp { .. } => 14,
        DecodedOperationKind::CreateMaskInstanceBuffer { .. } => 12,
        _ => unreachable!("quota offset is only requested for create operations"),
    }
}

fn stage_patch_image(
    gpu: &RetainedGpu,
    staged: &mut [StagedResource],
    actions: &mut Vec<PreparedAction<'_>>,
    operation_index: usize,
    operation: &DecodedOperation<'_>,
    resource_id: u32,
    x: u16,
    y: u16,
    width: u16,
    height: u16,
    pixels: Vec<u16>,
) -> Result<(), GuestRejection> {
    let staged_entry = live_staged_mut(staged, resource_id, operation, operation_index)?;
    match staged_entry.source {
        StagedSource::Created(index) => {
            let PreparedAction::Create { entry, .. } = &mut actions[index] else {
                unreachable!()
            };
            let Resource::ImageRgb565(image) = &mut entry.value else {
                return Err(operation_rejection(
                    operation,
                    operation_index,
                    ResultCode::InvalidResource,
                ));
            };
            validate_rect(
                image.width(),
                image.height(),
                x,
                y,
                width,
                height,
                pixels.len(),
                usize::from(width) * usize::from(height),
                operation,
                operation_index,
            )?;
            image
                .patch_rect(x, y, width, height, &pixels)
                .expect("created image patch was validated before mutation");
            entry.revision = entry
                .revision
                .checked_add(1)
                .expect("revision exhaustion checked before patch validation");
        }
        StagedSource::Existing(index) => {
            let Resource::ImageRgb565(image) = &gpu.resources[index].value else {
                return Err(operation_rejection(
                    operation,
                    operation_index,
                    ResultCode::InvalidResource,
                ));
            };
            validate_rect(
                image.width(),
                image.height(),
                x,
                y,
                width,
                height,
                pixels.len(),
                usize::from(width) * usize::from(height),
                operation,
                operation_index,
            )?;
            actions.push(PreparedAction::PatchImage {
                resource_id,
                x,
                y,
                width,
                height,
                pixels,
            });
        }
    }
    staged_entry.revision = staged_entry
        .revision
        .checked_add(1)
        .expect("revision exhaustion checked before patch validation");
    Ok(())
}

fn stage_patch_mask<'a>(
    gpu: &RetainedGpu,
    staged: &mut [StagedResource],
    actions: &mut Vec<PreparedAction<'a>>,
    operation_index: usize,
    operation: &DecodedOperation<'a>,
    resource_id: u32,
    x: u16,
    y: u16,
    width: u16,
    height: u16,
    rows: &'a [u8],
) -> Result<(), GuestRejection> {
    let staged_entry = live_staged_mut(staged, resource_id, operation, operation_index)?;
    match staged_entry.source {
        StagedSource::Created(index) => {
            let PreparedAction::Create { entry, .. } = &mut actions[index] else {
                unreachable!()
            };
            let Resource::Mask1Bpp(mask) = &mut entry.value else {
                return Err(operation_rejection(
                    operation,
                    operation_index,
                    ResultCode::InvalidResource,
                ));
            };
            validate_rect(
                mask.width(),
                mask.height(),
                x,
                y,
                width,
                height,
                rows.len(),
                usize::from(width).div_ceil(8) * usize::from(height),
                operation,
                operation_index,
            )?;
            mask.patch_rect(x, y, width, height, rows)
                .expect("created mask patch was validated before mutation");
            entry.revision = entry
                .revision
                .checked_add(1)
                .expect("revision exhaustion checked before patch validation");
        }
        StagedSource::Existing(index) => {
            let Resource::Mask1Bpp(mask) = &gpu.resources[index].value else {
                return Err(operation_rejection(
                    operation,
                    operation_index,
                    ResultCode::InvalidResource,
                ));
            };
            validate_rect(
                mask.width(),
                mask.height(),
                x,
                y,
                width,
                height,
                rows.len(),
                usize::from(width).div_ceil(8) * usize::from(height),
                operation,
                operation_index,
            )?;
            actions.push(PreparedAction::PatchMask {
                resource_id,
                x,
                y,
                width,
                height,
                rows,
            });
        }
    }
    staged_entry.revision = staged_entry
        .revision
        .checked_add(1)
        .expect("revision exhaustion checked before patch validation");
    Ok(())
}

fn stage_patch_instances(
    gpu: &RetainedGpu,
    staged: &mut [StagedResource],
    actions: &mut Vec<PreparedAction<'_>>,
    instance_overrides: &mut Vec<InstanceOverride>,
    operation_index: usize,
    operation: &DecodedOperation<'_>,
    resource_id: u32,
    start_index: u16,
    instances: Vec<MaskInstance>,
) -> Result<(), GuestRejection> {
    let staged_entry = live_staged_mut(staged, resource_id, operation, operation_index)?;
    match staged_entry.source {
        StagedSource::Created(index) => {
            let PreparedAction::Create { entry, .. } = &mut actions[index] else {
                unreachable!()
            };
            let Resource::MaskInstanceBuffer(buffer) = &mut entry.value else {
                return Err(operation_rejection(
                    operation,
                    operation_index,
                    ResultCode::InvalidResource,
                ));
            };
            if instances.is_empty()
                || usize::from(start_index) + instances.len() > buffer.instances().len()
            {
                return Err(operation_rejection_at(
                    operation,
                    operation_index,
                    if instances.is_empty() {
                        ResultCode::InvalidArgument
                    } else {
                        ResultCode::OutOfBounds
                    },
                    if instances.is_empty() { 14 } else { 12 },
                ));
            }
            buffer
                .patch(start_index, &instances)
                .expect("created instance patch was validated before mutation");
            entry.revision = entry
                .revision
                .checked_add(1)
                .expect("revision exhaustion checked before patch validation");
        }
        StagedSource::Existing(index) => {
            let Resource::MaskInstanceBuffer(buffer) = &gpu.resources[index].value else {
                return Err(operation_rejection(
                    operation,
                    operation_index,
                    ResultCode::InvalidResource,
                ));
            };
            if instances.is_empty()
                || usize::from(start_index) + instances.len() > buffer.instances().len()
            {
                return Err(operation_rejection_at(
                    operation,
                    operation_index,
                    if instances.is_empty() {
                        ResultCode::InvalidArgument
                    } else {
                        ResultCode::OutOfBounds
                    },
                    if instances.is_empty() { 14 } else { 12 },
                ));
            }
            for (relative_index, value) in instances.iter().copied().enumerate() {
                instance_overrides.push(InstanceOverride {
                    resource_id,
                    instance_index: usize::from(start_index) + relative_index,
                    operation_index,
                    byte_offset: operation.byte_offset + 16 + relative_index as u32 * 24,
                    value,
                });
            }
            actions.push(PreparedAction::PatchInstances {
                resource_id,
                start_index,
                instances,
            });
        }
    }
    staged_entry.revision = staged_entry
        .revision
        .checked_add(1)
        .expect("revision exhaustion checked before patch validation");
    Ok(())
}

fn stage_drop(
    gpu: &RetainedGpu,
    staged: &mut [StagedResource],
    actions: &mut Vec<PreparedAction<'_>>,
    operation_index: usize,
    operation: &DecodedOperation<'_>,
    resource_id: u32,
    total_payload: &mut usize,
) -> Result<(), GuestRejection> {
    let entry = live_staged_mut(staged, resource_id, operation, operation_index)?;
    let payload = match entry.source {
        StagedSource::Existing(index) => gpu.resources[index].value.payload_bytes(),
        StagedSource::Created(_) => {
            return Err(operation_rejection(
                operation,
                operation_index,
                ResultCode::InvalidResource,
            ))
        }
    };
    entry.live = false;
    entry.drop_operation = Some(operation_index);
    *total_payload -= payload;
    actions.push(PreparedAction::Drop { resource_id });
    Ok(())
}

fn live_staged_mut<'a>(
    staged: &'a mut [StagedResource],
    resource_id: u32,
    operation: &DecodedOperation<'_>,
    operation_index: usize,
) -> Result<&'a mut StagedResource, GuestRejection> {
    staged
        .iter_mut()
        .find(|entry| entry.id == resource_id && entry.live)
        .ok_or_else(|| operation_rejection(operation, operation_index, ResultCode::InvalidResource))
}

fn resolve_draw_list(
    gpu: &RetainedGpu,
    staged: &[StagedResource],
    actions: &[PreparedAction<'_>],
    instance_overrides: &[InstanceOverride],
    decoded: &DecodedDrawList,
) -> Result<Result<DrawList, SemanticError>, RetainedGpuFault> {
    let mut commands = Vec::new();
    commands
        .try_reserve_exact(decoded.commands.len())
        .map_err(|_| RetainedGpuFault::Allocation)?;
    let mut clip_offsets = [0u32; MAX_CLIP_DEPTH];
    let mut depth = 0usize;
    for command in &decoded.commands {
        let resolved = match command.kind {
            DecodedDrawCommandKind::PushClip {
                x,
                y,
                width,
                height,
            } => {
                if width == 0 || height == 0 {
                    return Ok(Err(draw_error(command, if width == 0 { 12 } else { 14 })));
                }
                if depth == MAX_CLIP_DEPTH {
                    return Ok(Err(draw_error(command, 0)));
                }
                clip_offsets[depth] = command.byte_offset;
                depth += 1;
                DrawCommand::PushClip {
                    x,
                    y,
                    width,
                    height,
                }
            }
            DecodedDrawCommandKind::PopClip => {
                if depth == 0 {
                    return Ok(Err(draw_error(command, 0)));
                }
                depth -= 1;
                DrawCommand::PopClip
            }
            DecodedDrawCommandKind::FillRect {
                x,
                y,
                width,
                height,
                rgb565,
            } => {
                if width == 0 || height == 0 {
                    return Ok(Err(draw_error(command, if width == 0 { 12 } else { 14 })));
                }
                DrawCommand::FillRect {
                    x,
                    y,
                    width,
                    height,
                    rgb565,
                }
            }
            DecodedDrawCommandKind::DrawImage {
                resource_id,
                source_x,
                source_y,
                source_width,
                source_height,
                destination_x,
                destination_y,
                destination_width,
                destination_height,
            } => {
                let Some((reference, resource)) =
                    staged_resource(gpu, staged, actions, resource_id)
                else {
                    return Ok(Err(draw_error(command, 8)));
                };
                let Resource::ImageRgb565(image) = resource else {
                    return Ok(Err(draw_error(command, 8)));
                };
                let source = SourceRect {
                    x: source_x,
                    y: source_y,
                    width: source_width,
                    height: source_height,
                };
                if let Some(relative_offset) =
                    source_rect_error_offset(source, image.width(), image.height())
                {
                    return Ok(Err(draw_error(command, relative_offset)));
                }
                if destination_width == 0 || destination_height == 0 {
                    return Ok(Err(draw_error(
                        command,
                        if destination_width == 0 { 24 } else { 26 },
                    )));
                }
                DrawCommand::DrawImage {
                    image: reference,
                    source,
                    destination: DestinationRect {
                        x: destination_x,
                        y: destination_y,
                        width: destination_width,
                        height: destination_height,
                    },
                }
            }
            DecodedDrawCommandKind::DrawMask {
                resource_id,
                source_x,
                source_y,
                source_width,
                source_height,
                destination_x,
                destination_y,
                destination_width,
                destination_height,
                foreground_rgb565,
                background_rgb565,
                opaque_background,
            } => {
                let Some((reference, resource)) =
                    staged_resource(gpu, staged, actions, resource_id)
                else {
                    return Ok(Err(draw_error(command, 8)));
                };
                let Resource::Mask1Bpp(mask) = resource else {
                    return Ok(Err(draw_error(command, 8)));
                };
                let source = SourceRect {
                    x: source_x,
                    y: source_y,
                    width: source_width,
                    height: source_height,
                };
                if let Some(relative_offset) =
                    source_rect_error_offset(source, mask.width(), mask.height())
                {
                    return Ok(Err(draw_error(command, relative_offset)));
                }
                if destination_width == 0 || destination_height == 0 {
                    return Ok(Err(draw_error(
                        command,
                        if destination_width == 0 { 24 } else { 26 },
                    )));
                }
                if !opaque_background && background_rgb565 != 0 {
                    return Ok(Err(draw_error(command, 30)));
                }
                DrawCommand::DrawMask {
                    mask: reference,
                    source,
                    destination: DestinationRect {
                        x: destination_x,
                        y: destination_y,
                        width: destination_width,
                        height: destination_height,
                    },
                    foreground_rgb565,
                    background_rgb565,
                    opaque_background,
                }
            }
            DecodedDrawCommandKind::DrawMaskInstances {
                mask_resource_id,
                instance_buffer_resource_id,
                first_instance,
                instance_count,
                translation_x,
                translation_y,
            } => {
                let Some((mask_ref, mask_resource)) =
                    staged_resource(gpu, staged, actions, mask_resource_id)
                else {
                    return Ok(Err(draw_error(command, 8)));
                };
                let Resource::Mask1Bpp(mask) = mask_resource else {
                    return Ok(Err(draw_error(command, 8)));
                };
                let Some((instances_ref, instances_resource)) =
                    staged_resource(gpu, staged, actions, instance_buffer_resource_id)
                else {
                    return Ok(Err(draw_error(command, 12)));
                };
                let Resource::MaskInstanceBuffer(buffer) = instances_resource else {
                    return Ok(Err(draw_error(command, 12)));
                };
                let start = usize::from(first_instance);
                let end = start + usize::from(instance_count);
                if instance_count == 0 || end > buffer.instances().len() {
                    return Ok(Err(draw_error(
                        command,
                        if instance_count == 0 { 18 } else { 16 },
                    )));
                }
                for index in start..end {
                    let (instance, _) = final_instance(
                        instance_overrides,
                        instance_buffer_resource_id,
                        buffer.instances()[index],
                        index,
                    );
                    let record = instance.record();
                    if u32::from(record.source_x) + u32::from(record.source_width)
                        > u32::from(mask.width())
                        || u32::from(record.source_y) + u32::from(record.source_height)
                            > u32::from(mask.height())
                    {
                        return Ok(Err(draw_error(command, 8)));
                    }
                }
                DrawCommand::DrawMaskInstances {
                    mask: mask_ref,
                    instances: instances_ref,
                    first_instance,
                    instance_count,
                    translation_x,
                    translation_y,
                }
            }
        };
        commands.push(resolved);
    }
    if depth != 0 {
        return Ok(Err(SemanticError {
            code: ResultCode::InvalidDrawList,
            byte_offset: clip_offsets[0],
        }));
    }
    Ok(Ok(DrawList::from_validated_parts(
        decoded.background_rgb565,
        commands,
        decoded.encoded_byte_len,
    )))
}

#[derive(Clone, Copy)]
struct SemanticError {
    code: ResultCode,
    byte_offset: u32,
}

fn draw_error(command: &super::packet::DecodedDrawCommand, relative_offset: u32) -> SemanticError {
    SemanticError {
        code: ResultCode::InvalidDrawList,
        byte_offset: command.byte_offset + relative_offset,
    }
}

fn staged_resource<'a>(
    gpu: &'a RetainedGpu,
    staged: &[StagedResource],
    actions: &'a [PreparedAction<'_>],
    id: u32,
) -> Option<(ResourceRef, &'a Resource)> {
    let entry = staged.iter().find(|entry| entry.id == id && entry.live)?;
    let value = match entry.source {
        StagedSource::Existing(index) => &gpu.resources[index].value,
        StagedSource::Created(index) => match &actions[index] {
            PreparedAction::Create { entry, .. } => &entry.value,
            _ => unreachable!(),
        },
    };
    Some((
        ResourceRef {
            id,
            incarnation: entry.incarnation,
        },
        value,
    ))
}

fn patch_semantics_are_valid(
    gpu: &RetainedGpu,
    staged: &[StagedResource],
    actions: &[PreparedAction<'_>],
    operation: &DecodedOperation<'_>,
) -> bool {
    match &operation.kind {
        DecodedOperationKind::PatchImageRect {
            resource_id,
            x,
            y,
            width,
            height,
            ..
        } => matches!(
            staged_resource(gpu, staged, actions, *resource_id),
            Some((_, Resource::ImageRgb565(image)))
                if rect_fits(image.width(), image.height(), *x, *y, *width, *height)
        ),
        DecodedOperationKind::PatchMaskRect {
            resource_id,
            x,
            y,
            width,
            height,
            ..
        } => matches!(
            staged_resource(gpu, staged, actions, *resource_id),
            Some((_, Resource::Mask1Bpp(mask)))
                if rect_fits(mask.width(), mask.height(), *x, *y, *width, *height)
        ),
        DecodedOperationKind::PatchMaskInstances {
            resource_id,
            start_index,
            count,
            ..
        } => matches!(
            staged_resource(gpu, staged, actions, *resource_id),
            Some((_, Resource::MaskInstanceBuffer(buffer)))
                if *count != 0
                    && usize::from(*start_index) + usize::from(*count)
                        <= buffer.instances().len()
        ),
        _ => false,
    }
}

fn rect_fits(
    resource_width: u16,
    resource_height: u16,
    x: u16,
    y: u16,
    width: u16,
    height: u16,
) -> bool {
    width != 0
        && height != 0
        && u32::from(x) + u32::from(width) <= u32::from(resource_width)
        && u32::from(y) + u32::from(height) <= u32::from(resource_height)
}

fn final_instance(
    instance_overrides: &[InstanceOverride],
    resource_id: u32,
    mut value: MaskInstance,
    index: usize,
) -> (MaskInstance, Option<InstanceOverride>) {
    let start = instance_overrides
        .partition_point(|entry| (entry.resource_id, entry.instance_index) < (resource_id, index));
    let end = instance_overrides
        .partition_point(|entry| (entry.resource_id, entry.instance_index) <= (resource_id, index));
    let contributor = instance_overrides[start..end].last();
    if let Some(contributor) = contributor {
        value = contributor.value;
    }
    (value, contributor.copied())
}

fn invalid_instance_field_offset(
    record: MaskInstanceRecord,
    mask_width: u16,
    mask_height: u16,
) -> u32 {
    if u32::from(record.source_x) + u32::from(record.source_width) > u32::from(mask_width) {
        if record.source_x >= mask_width {
            0
        } else {
            4
        }
    } else if record.source_y >= mask_height {
        2
    } else {
        6
    }
}

fn sort_instance_overrides(instance_overrides: &mut [InstanceOverride]) {
    instance_overrides.sort_unstable_by_key(|entry| {
        (
            entry.resource_id,
            entry.instance_index,
            entry.operation_index,
        )
    });
}

fn source_rect_error_offset(source: SourceRect, width: u16, height: u16) -> Option<u32> {
    if source.width == 0 {
        Some(16)
    } else if source.height == 0 {
        Some(18)
    } else if u32::from(source.x) + u32::from(source.width) > u32::from(width) {
        Some(if source.x >= width { 12 } else { 16 })
    } else if u32::from(source.y) + u32::from(source.height) > u32::from(height) {
        Some(if source.y >= height { 14 } else { 18 })
    } else {
        None
    }
}

fn decode_pixels(bytes: &[u8]) -> Result<Vec<u16>, RetainedGpuFault> {
    let mut values = Vec::new();
    values
        .try_reserve_exact(bytes.len() / 2)
        .map_err(|_| RetainedGpuFault::Allocation)?;
    for pair in bytes.chunks_exact(2) {
        values.push(u16::from_le_bytes([pair[0], pair[1]]));
    }
    Ok(values)
}
fn try_copy(bytes: &[u8]) -> Result<Vec<u8>, RetainedGpuFault> {
    let mut copy = Vec::new();
    copy.try_reserve_exact(bytes.len())
        .map_err(|_| RetainedGpuFault::Allocation)?;
    copy.extend_from_slice(bytes);
    Ok(copy)
}
fn decode_instances(bytes: &[u8]) -> Result<Vec<MaskInstance>, RetainedGpuFault> {
    let mut values = Vec::new();
    values
        .try_reserve_exact(bytes.len() / 24)
        .map_err(|_| RetainedGpuFault::Allocation)?;
    for record in bytes.chunks_exact(24) {
        let field = |offset| u16::from_le_bytes([record[offset], record[offset + 1]]);
        values.push(
            MaskInstance::new(MaskInstanceRecord {
                source_x: field(0),
                source_y: field(2),
                source_width: field(4),
                source_height: field(6),
                destination_x: field(8) as i16,
                destination_y: field(10) as i16,
                destination_width: field(12),
                destination_height: field(14),
                foreground_rgb565: field(16),
                background_rgb565: field(18),
                flags: field(20),
                reserved: field(22),
            })
            .map_err(|_| RetainedGpuFault::CorruptState)?,
        );
    }
    Ok(values)
}

fn validate_rect(
    resource_width: u16,
    resource_height: u16,
    x: u16,
    y: u16,
    width: u16,
    height: u16,
    actual: usize,
    expected: usize,
    operation: &DecodedOperation<'_>,
    operation_index: usize,
) -> Result<(), GuestRejection> {
    if width == 0 || height == 0 || actual != expected {
        let relative_offset = if width == 0 {
            16
        } else if height == 0 {
            18
        } else {
            20
        };
        return Err(operation_rejection_at(
            operation,
            operation_index,
            ResultCode::InvalidArgument,
            relative_offset,
        ));
    }
    if u32::from(x) + u32::from(width) > u32::from(resource_width) {
        return Err(operation_rejection_at(
            operation,
            operation_index,
            ResultCode::OutOfBounds,
            if x >= resource_width { 12 } else { 16 },
        ));
    }
    if u32::from(y) + u32::from(height) > u32::from(resource_height) {
        return Err(operation_rejection_at(
            operation,
            operation_index,
            ResultCode::OutOfBounds,
            if y >= resource_height { 14 } else { 18 },
        ));
    }
    Ok(())
}

fn operation_rejection(
    operation: &DecodedOperation<'_>,
    operation_index: usize,
    code: ResultCode,
) -> GuestRejection {
    operation_rejection_at(operation, operation_index, code, 8)
}
fn operation_rejection_at(
    operation: &DecodedOperation<'_>,
    operation_index: usize,
    code: ResultCode,
    relative_offset: u32,
) -> GuestRejection {
    GuestRejection {
        code,
        operation_index: operation_index as u32,
        byte_offset: operation.byte_offset + relative_offset,
    }
}
fn reject(code: ResultCode, operation_index: u32, byte_offset: u32) -> SubmissionOutcome {
    SubmissionOutcome::Rejected(GuestRejection {
        code,
        operation_index,
        byte_offset,
    })
}
fn find_resource_mut(
    resources: &mut [ResourceEntry],
    resource_id: u32,
) -> Result<&mut ResourceEntry, RetainedGpuFault> {
    resources
        .iter_mut()
        .find(|entry| entry.id == resource_id)
        .ok_or(RetainedGpuFault::CorruptState)
}

#[cfg(test)]
mod tests {
    use super::*;

    const CREATE_IMAGE: u16 = 0x0001;
    const CREATE_MASK: u16 = 0x0002;
    const CREATE_INSTANCES: u16 = 0x0003;
    const PATCH_IMAGE: u16 = 0x0010;
    const PATCH_MASK: u16 = 0x0011;
    const PATCH_INSTANCES: u16 = 0x0012;

    #[test]
    fn every_valid_patch_kind_reports_revision_exhaustion_as_a_host_fault() {
        for (create, patch) in [
            (create_image(), patch_rect(PATCH_IMAGE, 0, 0, 1, 1, &[1, 0])),
            (create_mask(), patch_rect(PATCH_MASK, 0, 0, 8, 1, &[0xff])),
            (create_instances(), patch_instances(0, 1)),
        ] {
            let mut gpu = RetainedGpu::try_new().expect("gpu");
            assert!(matches!(
                gpu.submit(&packet(0, &[create])).expect("create"),
                SubmissionOutcome::Committed { sequence: 1 }
            ));
            gpu.resources[0].revision = u64::MAX;

            assert!(matches!(
                gpu.submit(&packet(1, &[patch])),
                Err(RetainedGpuFault::CounterExhausted)
            ));
            assert_eq!(gpu.commit_sequence, 1);
            assert_eq!(gpu.resources[0].revision, u64::MAX);
        }
    }

    #[test]
    fn invalid_patch_semantics_precede_revision_exhaustion() {
        let mut gpu = RetainedGpu::try_new().expect("gpu");
        assert!(matches!(
            gpu.submit(&packet(0, &[create_image()])).expect("create"),
            SubmissionOutcome::Committed { sequence: 1 }
        ));
        gpu.resources[0].revision = u64::MAX;

        let SubmissionOutcome::Rejected(wrong_kind) = gpu
            .submit(&packet(1, &[patch_rect(PATCH_MASK, 0, 0, 8, 1, &[0xff])]))
            .expect("wrong-kind rejection")
        else {
            panic!("expected rejection");
        };
        assert_eq!(wrong_kind.code, ResultCode::InvalidResource);
        assert_eq!(wrong_kind.byte_offset, 32);

        let SubmissionOutcome::Rejected(out_of_bounds) = gpu
            .submit(&packet(1, &[patch_rect(PATCH_IMAGE, 1, 0, 1, 1, &[1, 0])]))
            .expect("out-of-bounds rejection")
        else {
            panic!("expected rejection");
        };
        assert_eq!(out_of_bounds.code, ResultCode::OutOfBounds);
        assert_eq!(out_of_bounds.byte_offset, 36);
        assert_eq!(gpu.commit_sequence, 1);
        assert_eq!(gpu.resources[0].revision, u64::MAX);
    }

    #[test]
    fn invalid_create_shape_precedes_incarnation_exhaustion() {
        for (create, byte_offset) in [
            (create_image_with_size(0, 1), 36),
            (create_mask_with_size(8, 0), 38),
            (create_instances_with_capacity(0), 36),
        ] {
            let mut gpu = RetainedGpu::try_new().expect("gpu");
            gpu.next_incarnation = u64::MAX;

            let SubmissionOutcome::Rejected(rejection) =
                gpu.submit(&packet(0, &[create])).expect("shape rejection")
            else {
                panic!("expected rejection");
            };
            assert_eq!(rejection.code, ResultCode::InvalidArgument);
            assert_eq!(rejection.byte_offset, byte_offset);
            assert_eq!(gpu.commit_sequence, 0);
            assert_eq!(gpu.next_incarnation, u64::MAX);
        }
    }

    fn create_image() -> Vec<u8> {
        create_image_with_size(1, 1)
    }

    fn create_image_with_size(width: u16, height: u16) -> Vec<u8> {
        let mut body = Vec::new();
        push_u32(&mut body, 1);
        push_u16(&mut body, width);
        push_u16(&mut body, height);
        body.resize(8 + usize::from(width) * usize::from(height) * 2, 0);
        operation(CREATE_IMAGE, &body)
    }

    fn create_mask() -> Vec<u8> {
        create_mask_with_size(8, 1)
    }

    fn create_mask_with_size(width: u16, height: u16) -> Vec<u8> {
        let mut body = Vec::new();
        push_u32(&mut body, 1);
        push_u16(&mut body, width);
        push_u16(&mut body, height);
        body.resize(
            8 + usize::from(width).div_ceil(8) * usize::from(height),
            0xff,
        );
        operation(CREATE_MASK, &body)
    }

    fn create_instances() -> Vec<u8> {
        create_instances_with_capacity(1)
    }

    fn create_instances_with_capacity(capacity: u16) -> Vec<u8> {
        let mut body = Vec::new();
        push_u32(&mut body, 1);
        push_u16(&mut body, capacity);
        push_u16(&mut body, 0);
        for _ in 0..capacity {
            body.extend_from_slice(&instance_record());
        }
        operation(CREATE_INSTANCES, &body)
    }

    fn patch_rect(opcode: u16, x: u16, y: u16, width: u16, height: u16, payload: &[u8]) -> Vec<u8> {
        let mut body = Vec::new();
        push_u32(&mut body, 1);
        for value in [x, y, width, height] {
            push_u16(&mut body, value);
        }
        body.extend_from_slice(payload);
        operation(opcode, &body)
    }

    fn patch_instances(start: u16, count: u16) -> Vec<u8> {
        let mut body = Vec::new();
        push_u32(&mut body, 1);
        push_u16(&mut body, start);
        push_u16(&mut body, count);
        for _ in 0..count {
            body.extend_from_slice(&instance_record());
        }
        operation(PATCH_INSTANCES, &body)
    }

    fn instance_record() -> [u8; 24] {
        let mut bytes = [0; 24];
        bytes[4..6].copy_from_slice(&8u16.to_le_bytes());
        bytes[6..8].copy_from_slice(&1u16.to_le_bytes());
        bytes[12..14].copy_from_slice(&8u16.to_le_bytes());
        bytes[14..16].copy_from_slice(&1u16.to_le_bytes());
        bytes[16..18].copy_from_slice(&u16::MAX.to_le_bytes());
        bytes[20..22].copy_from_slice(&1u16.to_le_bytes());
        bytes
    }

    fn operation(opcode: u16, body: &[u8]) -> Vec<u8> {
        let mut bytes = Vec::new();
        push_u16(&mut bytes, opcode);
        push_u16(&mut bytes, 0);
        push_u32(&mut bytes, (8 + body.len()) as u32);
        bytes.extend_from_slice(body);
        bytes
    }

    fn packet(base: u64, operations: &[Vec<u8>]) -> Vec<u8> {
        let mut bytes = Vec::new();
        push_u32(&mut bytes, 0x5550_474b);
        push_u16(&mut bytes, 1);
        push_u16(&mut bytes, 0);
        push_u32(&mut bytes, 0);
        push_u32(&mut bytes, operations.len() as u32);
        bytes.extend_from_slice(&base.to_le_bytes());
        for operation in operations {
            bytes.extend_from_slice(operation);
            bytes.resize(bytes.len().next_multiple_of(4), 0);
        }
        let length = bytes.len() as u32;
        bytes[8..12].copy_from_slice(&length.to_le_bytes());
        bytes
    }

    fn push_u16(bytes: &mut Vec<u8>, value: u16) {
        bytes.extend_from_slice(&value.to_le_bytes());
    }

    fn push_u32(bytes: &mut Vec<u8>, value: u32) {
        bytes.extend_from_slice(&value.to_le_bytes());
    }
}
