use super::packet::{
    decode_packet, DecodedDrawCommandKind, DecodedDrawList, DecodedOperation, DecodedOperationKind,
    PacketDecodeError,
};
use super::{
    DestinationRect, DrawCommand, DrawList, ImageRgb565, Mask1Bpp, MaskInstance,
    MaskInstanceBuffer, MaskInstanceRecord, Resource, ResourceEntry, ResourceRef, ResultCode,
    SourceRect, MAX_CLIP_DEPTH, MAX_DRAW_LIST_BYTES, MAX_RESOURCES, MAX_RESOURCE_BYTES,
    MAX_TOTAL_RESOURCE_BYTES,
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

    fn commit(&mut self, mut prepared: PreparedTransaction<'_>) -> Result<(), RetainedGpuFault> {
        prepared
            .actions
            .sort_by_key(PreparedAction::operation_index);
        for action in prepared.actions {
            match action {
                PreparedAction::Create { entry, .. } => {
                    let index = self
                        .resources
                        .binary_search_by_key(&entry.id, |resource| resource.id)
                        .unwrap_or_else(|index| index);
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
        operation_index: usize,
        entry: ResourceEntry,
    },
    PatchImage {
        operation_index: usize,
        resource_id: u32,
        x: u16,
        y: u16,
        width: u16,
        height: u16,
        pixels: Vec<u16>,
    },
    PatchMask {
        operation_index: usize,
        resource_id: u32,
        x: u16,
        y: u16,
        width: u16,
        height: u16,
        rows: &'a [u8],
    },
    PatchInstances {
        operation_index: usize,
        resource_id: u32,
        start_index: u16,
        instances: Vec<MaskInstance>,
    },
    Drop {
        operation_index: usize,
        resource_id: u32,
    },
}

impl PreparedAction<'_> {
    fn operation_index(&self) -> usize {
        match self {
            Self::Create {
                operation_index, ..
            }
            | Self::PatchImage {
                operation_index, ..
            }
            | Self::PatchMask {
                operation_index, ..
            }
            | Self::PatchInstances {
                operation_index, ..
            }
            | Self::Drop {
                operation_index, ..
            } => *operation_index,
        }
    }
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
            .try_reserve_exact(MAX_RESOURCES)
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
                            return Ok(Err(operation_rejection(
                                operation,
                                operation_index,
                                ResultCode::InvalidArgument,
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
                            return Ok(Err(operation_rejection(
                                operation,
                                operation_index,
                                ResultCode::InvalidArgument,
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
                            return Ok(Err(operation_rejection(
                                operation,
                                operation_index,
                                ResultCode::InvalidArgument,
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
                    match resolve_draw_list(gpu, &staged, &actions, decoded)? {
                        Ok(resolved) => {
                            draw_list = Some(resolved);
                            Ok(())
                        }
                        Err(code) => Err(operation_rejection(operation, operation_index, code)),
                    }
                }
            };
            if let Err(rejection) = result {
                return Ok(Err(rejection));
            }
        }

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
        Ok(Ok(Self {
            actions,
            draw_list,
            next_incarnation,
        }))
    }
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
    if payload > MAX_RESOURCE_BYTES
        || staged.iter().filter(|entry| entry.live).count() == MAX_RESOURCES
    {
        return Err(operation_rejection(
            operation,
            operation_index,
            ResultCode::QuotaExceeded,
        ));
    }
    let next_total = total_payload.checked_add(payload).ok_or_else(|| {
        operation_rejection(operation, operation_index, ResultCode::QuotaExceeded)
    })?;
    if next_total > MAX_TOTAL_RESOURCE_BYTES {
        return Err(operation_rejection(
            operation,
            operation_index,
            ResultCode::QuotaExceeded,
        ));
    }
    let incarnation = *next_incarnation;
    *next_incarnation = next_incarnation
        .checked_add(1)
        .expect("counter exhaustion checked before resource allocation");
    let created_index = actions.len();
    actions.push(PreparedAction::Create {
        operation_index,
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
    if payload_bytes > MAX_RESOURCE_BYTES
        || staged.iter().filter(|entry| entry.live).count() == MAX_RESOURCES
        || total_payload
            .checked_add(payload_bytes)
            .is_none_or(|total| total > MAX_TOTAL_RESOURCE_BYTES)
    {
        return Err(operation_rejection(
            operation,
            operation_index,
            ResultCode::QuotaExceeded,
        ));
    }
    Ok(())
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
            image
                .patch_rect(x, y, width, height, &pixels)
                .map_err(|error| resource_patch_rejection(operation, operation_index, error))?;
            entry.revision += 1;
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
                operation_index,
                resource_id,
                x,
                y,
                width,
                height,
                pixels,
            });
        }
    }
    staged_entry.revision = staged_entry.revision.checked_add(1).ok_or_else(|| {
        operation_rejection(operation, operation_index, ResultCode::QuotaExceeded)
    })?;
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
            mask.patch_rect(x, y, width, height, rows)
                .map_err(|error| resource_patch_rejection(operation, operation_index, error))?;
            entry.revision += 1;
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
                operation_index,
                resource_id,
                x,
                y,
                width,
                height,
                rows,
            });
        }
    }
    staged_entry.revision += 1;
    Ok(())
}

fn stage_patch_instances(
    gpu: &RetainedGpu,
    staged: &mut [StagedResource],
    actions: &mut Vec<PreparedAction<'_>>,
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
            buffer
                .patch(start_index, &instances)
                .map_err(|error| resource_patch_rejection(operation, operation_index, error))?;
            entry.revision += 1;
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
                return Err(operation_rejection(
                    operation,
                    operation_index,
                    if instances.is_empty() {
                        ResultCode::InvalidArgument
                    } else {
                        ResultCode::OutOfBounds
                    },
                ));
            }
            actions.push(PreparedAction::PatchInstances {
                operation_index,
                resource_id,
                start_index,
                instances,
            });
        }
    }
    staged_entry.revision += 1;
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
    actions.push(PreparedAction::Drop {
        operation_index,
        resource_id,
    });
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
    decoded: &DecodedDrawList,
) -> Result<Result<DrawList, ResultCode>, RetainedGpuFault> {
    if decoded.encoded_byte_len > MAX_DRAW_LIST_BYTES {
        return Ok(Err(ResultCode::QuotaExceeded));
    }
    let mut commands = Vec::new();
    commands
        .try_reserve_exact(decoded.commands.len())
        .map_err(|_| RetainedGpuFault::Allocation)?;
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
                    return Ok(Err(ResultCode::InvalidDrawList));
                }
                depth += 1;
                if depth > MAX_CLIP_DEPTH {
                    return Ok(Err(ResultCode::InvalidDrawList));
                }
                DrawCommand::PushClip {
                    x,
                    y,
                    width,
                    height,
                }
            }
            DecodedDrawCommandKind::PopClip => {
                if depth == 0 {
                    return Ok(Err(ResultCode::InvalidDrawList));
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
                    return Ok(Err(ResultCode::InvalidDrawList));
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
                    return Ok(Err(ResultCode::InvalidDrawList));
                };
                let Resource::ImageRgb565(image) = resource else {
                    return Ok(Err(ResultCode::InvalidDrawList));
                };
                let source = SourceRect {
                    x: source_x,
                    y: source_y,
                    width: source_width,
                    height: source_height,
                };
                if !source_valid(source, image.width(), image.height())
                    || destination_width == 0
                    || destination_height == 0
                {
                    return Ok(Err(ResultCode::InvalidDrawList));
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
                    return Ok(Err(ResultCode::InvalidDrawList));
                };
                let Resource::Mask1Bpp(mask) = resource else {
                    return Ok(Err(ResultCode::InvalidDrawList));
                };
                let source = SourceRect {
                    x: source_x,
                    y: source_y,
                    width: source_width,
                    height: source_height,
                };
                if !source_valid(source, mask.width(), mask.height())
                    || destination_width == 0
                    || destination_height == 0
                    || (!opaque_background && background_rgb565 != 0)
                {
                    return Ok(Err(ResultCode::InvalidDrawList));
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
                    return Ok(Err(ResultCode::InvalidDrawList));
                };
                let Resource::Mask1Bpp(mask) = mask_resource else {
                    return Ok(Err(ResultCode::InvalidDrawList));
                };
                let Some((instances_ref, instances_resource)) =
                    staged_resource(gpu, staged, actions, instance_buffer_resource_id)
                else {
                    return Ok(Err(ResultCode::InvalidDrawList));
                };
                let Resource::MaskInstanceBuffer(buffer) = instances_resource else {
                    return Ok(Err(ResultCode::InvalidDrawList));
                };
                let start = usize::from(first_instance);
                let end = start + usize::from(instance_count);
                if instance_count == 0 || end > buffer.instances().len() {
                    return Ok(Err(ResultCode::InvalidDrawList));
                }
                for index in start..end {
                    let instance = final_instance(
                        actions,
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
                        return Ok(Err(ResultCode::InvalidDrawList));
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
        return Ok(Err(ResultCode::InvalidDrawList));
    }
    Ok(Ok(DrawList::from_validated_parts(
        decoded.background_rgb565,
        commands,
        decoded.encoded_byte_len,
    )))
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

fn final_instance(
    actions: &[PreparedAction<'_>],
    resource_id: u32,
    mut value: MaskInstance,
    index: usize,
) -> MaskInstance {
    for action in actions {
        if let PreparedAction::PatchInstances {
            resource_id: id,
            start_index,
            instances,
            ..
        } = action
        {
            let start = usize::from(*start_index);
            if *id == resource_id && (start..start + instances.len()).contains(&index) {
                value = instances[index - start];
            }
        }
    }
    value
}

fn source_valid(source: SourceRect, width: u16, height: u16) -> bool {
    source.width != 0
        && source.height != 0
        && u32::from(source.x) + u32::from(source.width) <= u32::from(width)
        && u32::from(source.y) + u32::from(source.height) <= u32::from(height)
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
        return Err(operation_rejection(
            operation,
            operation_index,
            ResultCode::InvalidArgument,
        ));
    }
    if u32::from(x) + u32::from(width) > u32::from(resource_width)
        || u32::from(y) + u32::from(height) > u32::from(resource_height)
    {
        return Err(operation_rejection(
            operation,
            operation_index,
            ResultCode::OutOfBounds,
        ));
    }
    Ok(())
}

fn resource_patch_rejection(
    operation: &DecodedOperation<'_>,
    operation_index: usize,
    error: super::ResourceValidationError,
) -> GuestRejection {
    let code = match error {
        super::ResourceValidationError::OutOfBounds => ResultCode::OutOfBounds,
        _ => ResultCode::InvalidArgument,
    };
    operation_rejection(operation, operation_index, code)
}
fn operation_rejection(
    operation: &DecodedOperation<'_>,
    operation_index: usize,
    code: ResultCode,
) -> GuestRejection {
    GuestRejection {
        code,
        operation_index: operation_index as u32,
        byte_offset: operation.byte_offset + 8,
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
