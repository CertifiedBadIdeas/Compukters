use super::{Resource, RetainedGpu, RetainedGpuFault};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ResourceKind {
    ImageRgb565,
    Mask1Bpp,
    MaskInstanceBuffer,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ManifestEntry {
    pub resource_id: u32,
    pub incarnation: u64,
    pub revision: u64,
    pub kind: ResourceKind,
    pub width_or_capacity: u16,
    pub height: u16,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ResourceManifest {
    sequence: u64,
    entries: Vec<ManifestEntry>,
}

impl ResourceManifest {
    pub fn try_from_gpu(gpu: &RetainedGpu) -> Result<Self, RetainedGpuFault> {
        let mut entries = Vec::new();
        entries
            .try_reserve_exact(gpu.resources().len())
            .map_err(|_| RetainedGpuFault::Allocation)?;
        for entry in gpu.resources() {
            let (kind, width_or_capacity, height) = match &entry.value {
                Resource::ImageRgb565(image) => {
                    (ResourceKind::ImageRgb565, image.width(), image.height())
                }
                Resource::Mask1Bpp(mask) => (ResourceKind::Mask1Bpp, mask.width(), mask.height()),
                Resource::MaskInstanceBuffer(buffer) => {
                    (ResourceKind::MaskInstanceBuffer, buffer.capacity(), 0)
                }
            };
            entries.push(ManifestEntry {
                resource_id: entry.id,
                incarnation: entry.incarnation,
                revision: entry.revision,
                kind,
                width_or_capacity,
                height,
            });
        }
        Ok(Self {
            sequence: gpu.commit_sequence(),
            entries,
        })
    }

    pub fn sequence(&self) -> u64 {
        self.sequence
    }

    pub fn entries(&self) -> &[ManifestEntry] {
        &self.entries
    }

    pub(crate) fn entry(&self, resource_id: u32) -> Option<&ManifestEntry> {
        self.entries
            .binary_search_by_key(&resource_id, |entry| entry.resource_id)
            .ok()
            .map(|index| &self.entries[index])
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DamageRect {
    pub x: u16,
    pub y: u16,
    pub width: u16,
    pub height: u16,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DamageRange {
    pub start_index: u16,
    pub count: u16,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ResourceDamage {
    Created {
        resource_id: u32,
        incarnation: u64,
    },
    ImagePatches {
        resource_id: u32,
        incarnation: u64,
        rectangles: Vec<DamageRect>,
    },
    MaskPatches {
        resource_id: u32,
        incarnation: u64,
        rectangles: Vec<DamageRect>,
    },
    InstancePatches {
        resource_id: u32,
        incarnation: u64,
        ranges: Vec<DamageRange>,
    },
    Dropped {
        resource_id: u32,
        incarnation: u64,
    },
}

impl ResourceDamage {
    pub fn resource_id(&self) -> u32 {
        match self {
            Self::Created { resource_id, .. }
            | Self::ImagePatches { resource_id, .. }
            | Self::MaskPatches { resource_id, .. }
            | Self::InstancePatches { resource_id, .. }
            | Self::Dropped { resource_id, .. } => *resource_id,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CommittedDamage {
    base_sequence: u64,
    target_sequence: u64,
    changes: Vec<ResourceDamage>,
    draw_list_replaced: bool,
}

impl CommittedDamage {
    pub(crate) fn try_new(
        base_sequence: u64,
        target_sequence: u64,
        change_capacity: usize,
        draw_list_replaced: bool,
    ) -> Result<Self, RetainedGpuFault> {
        let mut changes = Vec::new();
        changes
            .try_reserve_exact(change_capacity)
            .map_err(|_| RetainedGpuFault::Allocation)?;
        Ok(Self {
            base_sequence,
            target_sequence,
            changes,
            draw_list_replaced,
        })
    }

    pub fn base_sequence(&self) -> u64 {
        self.base_sequence
    }

    pub fn target_sequence(&self) -> u64 {
        self.target_sequence
    }

    pub fn changes(&self) -> &[ResourceDamage] {
        &self.changes
    }

    pub fn draw_list_replaced(&self) -> bool {
        self.draw_list_replaced
    }

    pub fn descriptor_payload_bytes(&self) -> usize {
        0
    }

    pub(crate) fn push_created(&mut self, resource_id: u32, incarnation: u64) {
        self.changes.push(ResourceDamage::Created {
            resource_id,
            incarnation,
        });
    }

    pub(crate) fn push_dropped(&mut self, resource_id: u32, incarnation: u64) {
        self.changes.push(ResourceDamage::Dropped {
            resource_id,
            incarnation,
        });
    }

    pub(crate) fn try_push_image_patch(
        &mut self,
        resource_id: u32,
        incarnation: u64,
        rectangle: DamageRect,
    ) -> Result<(), RetainedGpuFault> {
        self.try_push_rect(resource_id, incarnation, rectangle, true)
    }

    pub(crate) fn try_push_mask_patch(
        &mut self,
        resource_id: u32,
        incarnation: u64,
        rectangle: DamageRect,
    ) -> Result<(), RetainedGpuFault> {
        self.try_push_rect(resource_id, incarnation, rectangle, false)
    }

    fn try_push_rect(
        &mut self,
        resource_id: u32,
        incarnation: u64,
        rectangle: DamageRect,
        image: bool,
    ) -> Result<(), RetainedGpuFault> {
        if let Some(change) = self
            .changes
            .iter_mut()
            .find(|change| change.resource_id() == resource_id)
        {
            let rectangles = match change {
                ResourceDamage::ImagePatches { rectangles, .. } if image => rectangles,
                ResourceDamage::MaskPatches { rectangles, .. } if !image => rectangles,
                _ => return Ok(()),
            };
            merge_rect(rectangles, rectangle)?;
            return Ok(());
        }
        let mut rectangles = Vec::new();
        rectangles
            .try_reserve_exact(1)
            .map_err(|_| RetainedGpuFault::Allocation)?;
        rectangles.push(rectangle);
        self.changes.push(if image {
            ResourceDamage::ImagePatches {
                resource_id,
                incarnation,
                rectangles,
            }
        } else {
            ResourceDamage::MaskPatches {
                resource_id,
                incarnation,
                rectangles,
            }
        });
        Ok(())
    }

    pub(crate) fn try_push_instance_patch(
        &mut self,
        resource_id: u32,
        incarnation: u64,
        range: DamageRange,
    ) -> Result<(), RetainedGpuFault> {
        if let Some(change) = self
            .changes
            .iter_mut()
            .find(|change| change.resource_id() == resource_id)
        {
            if let ResourceDamage::InstancePatches { ranges, .. } = change {
                merge_range(ranges, range)?;
            }
            return Ok(());
        }
        let mut ranges = Vec::new();
        ranges
            .try_reserve_exact(1)
            .map_err(|_| RetainedGpuFault::Allocation)?;
        ranges.push(range);
        self.changes.push(ResourceDamage::InstancePatches {
            resource_id,
            incarnation,
            ranges,
        });
        Ok(())
    }

    pub(crate) fn finish(mut self) -> Self {
        self.changes
            .sort_unstable_by_key(ResourceDamage::resource_id);
        self
    }
}

fn merge_range(ranges: &mut Vec<DamageRange>, next: DamageRange) -> Result<(), RetainedGpuFault> {
    let next_start = u32::from(next.start_index);
    let next_end = next_start + u32::from(next.count);
    for range in ranges.iter_mut() {
        let start = u32::from(range.start_index);
        let end = start + u32::from(range.count);
        if next_start <= end && start <= next_end {
            let merged_start = start.min(next_start);
            let merged_end = end.max(next_end);
            range.start_index = merged_start as u16;
            range.count = (merged_end - merged_start) as u16;
            return Ok(());
        }
    }
    ranges
        .try_reserve(1)
        .map_err(|_| RetainedGpuFault::Allocation)?;
    ranges.push(next);
    Ok(())
}

fn merge_rect(rectangles: &mut Vec<DamageRect>, next: DamageRect) -> Result<(), RetainedGpuFault> {
    let nx0 = u32::from(next.x);
    let ny0 = u32::from(next.y);
    let nx1 = nx0 + u32::from(next.width);
    let ny1 = ny0 + u32::from(next.height);
    for rectangle in rectangles.iter_mut() {
        let x0 = u32::from(rectangle.x);
        let y0 = u32::from(rectangle.y);
        let x1 = x0 + u32::from(rectangle.width);
        let y1 = y0 + u32::from(rectangle.height);
        if nx0 <= x1 && x0 <= nx1 && ny0 <= y1 && y0 <= ny1 {
            let merged_x0 = x0.min(nx0);
            let merged_y0 = y0.min(ny0);
            let merged_x1 = x1.max(nx1);
            let merged_y1 = y1.max(ny1);
            rectangle.x = merged_x0 as u16;
            rectangle.y = merged_y0 as u16;
            rectangle.width = (merged_x1 - merged_x0) as u16;
            rectangle.height = (merged_y1 - merged_y0) as u16;
            return Ok(());
        }
    }
    rectangles
        .try_reserve(1)
        .map_err(|_| RetainedGpuFault::Allocation)?;
    rectangles.push(next);
    Ok(())
}
