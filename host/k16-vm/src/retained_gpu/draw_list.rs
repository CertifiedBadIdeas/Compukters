use super::{Mask1Bpp, MaskInstanceBuffer, Resource, ResourceEntry, ResourceRef};

pub const MAX_DRAW_LIST_BYTES: usize = 65_536;
pub const MAX_DRAW_COMMANDS: usize = 2_048;
pub const MAX_CLIP_DEPTH: usize = 32;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SourceRect {
    pub x: u16,
    pub y: u16,
    pub width: u16,
    pub height: u16,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DestinationRect {
    pub x: i16,
    pub y: i16,
    pub width: u16,
    pub height: u16,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum UnresolvedDrawCommand {
    PushClip {
        x: i16,
        y: i16,
        width: u16,
        height: u16,
    },
    PopClip,
    FillRect {
        x: i16,
        y: i16,
        width: u16,
        height: u16,
        rgb565: u16,
    },
    DrawImage {
        resource_id: u32,
        source: SourceRect,
        destination: DestinationRect,
    },
    DrawMask {
        resource_id: u32,
        source: SourceRect,
        destination: DestinationRect,
        foreground_rgb565: u16,
        background_rgb565: u16,
        opaque_background: bool,
    },
    DrawMaskInstances {
        mask_resource_id: u32,
        instance_buffer_resource_id: u32,
        first_instance: u16,
        instance_count: u16,
        translation_x: i16,
        translation_y: i16,
    },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DrawCommand {
    PushClip {
        x: i16,
        y: i16,
        width: u16,
        height: u16,
    },
    PopClip,
    FillRect {
        x: i16,
        y: i16,
        width: u16,
        height: u16,
        rgb565: u16,
    },
    DrawImage {
        image: ResourceRef,
        source: SourceRect,
        destination: DestinationRect,
    },
    DrawMask {
        mask: ResourceRef,
        source: SourceRect,
        destination: DestinationRect,
        foreground_rgb565: u16,
        background_rgb565: u16,
        opaque_background: bool,
    },
    DrawMaskInstances {
        mask: ResourceRef,
        instances: ResourceRef,
        first_instance: u16,
        instance_count: u16,
        translation_x: i16,
        translation_y: i16,
    },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DrawList {
    background_rgb565: u16,
    commands: Vec<DrawCommand>,
    encoded_byte_len: usize,
}

impl DrawList {
    pub(crate) fn from_validated_parts(
        background_rgb565: u16,
        commands: Vec<DrawCommand>,
        encoded_byte_len: usize,
    ) -> Self {
        Self {
            background_rgb565,
            commands,
            encoded_byte_len,
        }
    }

    pub fn resolve(
        background_rgb565: u16,
        commands: Vec<UnresolvedDrawCommand>,
        encoded_byte_len: usize,
        resources: &[ResourceEntry],
    ) -> Result<Self, DrawListValidationError> {
        if commands.len() > MAX_DRAW_COMMANDS {
            return Err(DrawListValidationError::CommandCountQuota);
        }
        if encoded_byte_len > MAX_DRAW_LIST_BYTES {
            return Err(DrawListValidationError::EncodedBytesQuota);
        }

        let mut resolved = Vec::with_capacity(commands.len());
        let mut clip_depth = 0usize;
        for (command_index, command) in commands.into_iter().enumerate() {
            let command = match command {
                UnresolvedDrawCommand::PushClip {
                    x,
                    y,
                    width,
                    height,
                } => {
                    require_extent(command_index, width, height)?;
                    if clip_depth == MAX_CLIP_DEPTH {
                        return Err(DrawListValidationError::ClipDepthExceeded { command_index });
                    }
                    clip_depth += 1;
                    DrawCommand::PushClip {
                        x,
                        y,
                        width,
                        height,
                    }
                }
                UnresolvedDrawCommand::PopClip => {
                    if clip_depth == 0 {
                        return Err(DrawListValidationError::ClipUnderflow { command_index });
                    }
                    clip_depth -= 1;
                    DrawCommand::PopClip
                }
                UnresolvedDrawCommand::FillRect {
                    x,
                    y,
                    width,
                    height,
                    rgb565,
                } => {
                    require_extent(command_index, width, height)?;
                    DrawCommand::FillRect {
                        x,
                        y,
                        width,
                        height,
                        rgb565,
                    }
                }
                UnresolvedDrawCommand::DrawImage {
                    resource_id,
                    source,
                    destination,
                } => {
                    require_rectangles(command_index, source, destination)?;
                    let entry = find_resource(resources, command_index, resource_id)?;
                    let Resource::ImageRgb565(image) = &entry.value else {
                        return Err(DrawListValidationError::WrongResourceKind {
                            command_index,
                            resource_id,
                        });
                    };
                    require_source_bounds(
                        command_index,
                        resource_id,
                        source,
                        image.width(),
                        image.height(),
                    )?;
                    DrawCommand::DrawImage {
                        image: resource_ref(entry),
                        source,
                        destination,
                    }
                }
                UnresolvedDrawCommand::DrawMask {
                    resource_id,
                    source,
                    destination,
                    foreground_rgb565,
                    background_rgb565,
                    opaque_background,
                } => {
                    require_rectangles(command_index, source, destination)?;
                    if !opaque_background && background_rgb565 != 0 {
                        return Err(DrawListValidationError::CutoutBackground { command_index });
                    }
                    let entry = find_resource(resources, command_index, resource_id)?;
                    let Resource::Mask1Bpp(mask) = &entry.value else {
                        return Err(DrawListValidationError::WrongResourceKind {
                            command_index,
                            resource_id,
                        });
                    };
                    require_source_bounds(
                        command_index,
                        resource_id,
                        source,
                        mask.width(),
                        mask.height(),
                    )?;
                    DrawCommand::DrawMask {
                        mask: resource_ref(entry),
                        source,
                        destination,
                        foreground_rgb565,
                        background_rgb565,
                        opaque_background,
                    }
                }
                UnresolvedDrawCommand::DrawMaskInstances {
                    mask_resource_id,
                    instance_buffer_resource_id,
                    first_instance,
                    instance_count,
                    translation_x,
                    translation_y,
                } => {
                    if instance_count == 0 {
                        return Err(DrawListValidationError::ZeroExtent { command_index });
                    }
                    let mask_entry = find_resource(resources, command_index, mask_resource_id)?;
                    let Resource::Mask1Bpp(mask) = &mask_entry.value else {
                        return Err(DrawListValidationError::WrongResourceKind {
                            command_index,
                            resource_id: mask_resource_id,
                        });
                    };
                    let instance_entry =
                        find_resource(resources, command_index, instance_buffer_resource_id)?;
                    let Resource::MaskInstanceBuffer(instances) = &instance_entry.value else {
                        return Err(DrawListValidationError::WrongResourceKind {
                            command_index,
                            resource_id: instance_buffer_resource_id,
                        });
                    };
                    validate_instance_range(
                        command_index,
                        instance_buffer_resource_id,
                        first_instance,
                        instance_count,
                        instances,
                    )?;
                    validate_instance_sources(
                        command_index,
                        mask_resource_id,
                        first_instance,
                        instance_count,
                        mask,
                        instances,
                    )?;
                    DrawCommand::DrawMaskInstances {
                        mask: resource_ref(mask_entry),
                        instances: resource_ref(instance_entry),
                        first_instance,
                        instance_count,
                        translation_x,
                        translation_y,
                    }
                }
            };
            resolved.push(command);
        }

        if clip_depth != 0 {
            return Err(DrawListValidationError::UnbalancedClips { depth: clip_depth });
        }
        Ok(Self {
            background_rgb565,
            commands: resolved,
            encoded_byte_len,
        })
    }

    pub fn background_rgb565(&self) -> u16 {
        self.background_rgb565
    }

    pub fn commands(&self) -> &[DrawCommand] {
        &self.commands
    }

    pub fn encoded_byte_len(&self) -> usize {
        self.encoded_byte_len
    }

    pub(crate) fn references(&self, reference: ResourceRef) -> bool {
        self.commands.iter().any(|command| match command {
            DrawCommand::DrawImage { image, .. } => *image == reference,
            DrawCommand::DrawMask { mask, .. } => *mask == reference,
            DrawCommand::DrawMaskInstances {
                mask, instances, ..
            } => *mask == reference || *instances == reference,
            DrawCommand::PushClip { .. } | DrawCommand::PopClip | DrawCommand::FillRect { .. } => {
                false
            }
        })
    }
}

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum DrawListValidationError {
    #[error("draw-list command count exceeds the quota")]
    CommandCountQuota,
    #[error("encoded draw-list byte length exceeds the quota")]
    EncodedBytesQuota,
    #[error("draw command {command_index} has a zero extent")]
    ZeroExtent { command_index: usize },
    #[error("draw command {command_index} references missing resource {resource_id}")]
    ResourceNotFound {
        command_index: usize,
        resource_id: u32,
    },
    #[error("draw command {command_index} references resource {resource_id} with the wrong kind")]
    WrongResourceKind {
        command_index: usize,
        resource_id: u32,
    },
    #[error("draw command {command_index} source is outside resource {resource_id}")]
    SourceOutOfBounds {
        command_index: usize,
        resource_id: u32,
    },
    #[error("draw command {command_index} instance range is outside resource {resource_id}")]
    InstanceRangeOutOfBounds {
        command_index: usize,
        resource_id: u32,
    },
    #[error(
        "draw command {command_index} instance {instance_index} source is outside mask {resource_id}"
    )]
    InstanceSourceOutOfBounds {
        command_index: usize,
        instance_index: usize,
        resource_id: u32,
    },
    #[error("draw command {command_index} pops the initial clip")]
    ClipUnderflow { command_index: usize },
    #[error("draw command {command_index} exceeds the clip-depth quota")]
    ClipDepthExceeded { command_index: usize },
    #[error("draw list finishes with {depth} nested clips")]
    UnbalancedClips { depth: usize },
    #[error("draw command {command_index} has a non-zero cutout background")]
    CutoutBackground { command_index: usize },
}

fn find_resource(
    resources: &[ResourceEntry],
    command_index: usize,
    resource_id: u32,
) -> Result<&ResourceEntry, DrawListValidationError> {
    resources
        .iter()
        .find(|entry| entry.id == resource_id)
        .ok_or(DrawListValidationError::ResourceNotFound {
            command_index,
            resource_id,
        })
}

fn resource_ref(entry: &ResourceEntry) -> ResourceRef {
    ResourceRef {
        id: entry.id,
        incarnation: entry.incarnation,
    }
}

fn require_extent(
    command_index: usize,
    width: u16,
    height: u16,
) -> Result<(), DrawListValidationError> {
    if width == 0 || height == 0 {
        Err(DrawListValidationError::ZeroExtent { command_index })
    } else {
        Ok(())
    }
}

fn require_rectangles(
    command_index: usize,
    source: SourceRect,
    destination: DestinationRect,
) -> Result<(), DrawListValidationError> {
    require_extent(command_index, source.width, source.height)?;
    require_extent(command_index, destination.width, destination.height)
}

fn require_source_bounds(
    command_index: usize,
    resource_id: u32,
    source: SourceRect,
    resource_width: u16,
    resource_height: u16,
) -> Result<(), DrawListValidationError> {
    let right = u32::from(source.x) + u32::from(source.width);
    let bottom = u32::from(source.y) + u32::from(source.height);
    if right > u32::from(resource_width) || bottom > u32::from(resource_height) {
        Err(DrawListValidationError::SourceOutOfBounds {
            command_index,
            resource_id,
        })
    } else {
        Ok(())
    }
}

fn validate_instance_range(
    command_index: usize,
    resource_id: u32,
    first_instance: u16,
    instance_count: u16,
    instances: &MaskInstanceBuffer,
) -> Result<(), DrawListValidationError> {
    let end = usize::from(first_instance) + usize::from(instance_count);
    if end > instances.instances().len() {
        Err(DrawListValidationError::InstanceRangeOutOfBounds {
            command_index,
            resource_id,
        })
    } else {
        Ok(())
    }
}

fn validate_instance_sources(
    command_index: usize,
    mask_resource_id: u32,
    first_instance: u16,
    instance_count: u16,
    mask: &Mask1Bpp,
    instances: &MaskInstanceBuffer,
) -> Result<(), DrawListValidationError> {
    let start = usize::from(first_instance);
    let end = start + usize::from(instance_count);
    for (instance_index, instance) in instances.instances()[start..end].iter().enumerate() {
        let record = instance.record();
        let source = SourceRect {
            x: record.source_x,
            y: record.source_y,
            width: record.source_width,
            height: record.source_height,
        };
        if require_source_bounds(
            command_index,
            mask_resource_id,
            source,
            mask.width(),
            mask.height(),
        )
        .is_err()
        {
            return Err(DrawListValidationError::InstanceSourceOutOfBounds {
                command_index,
                instance_index: start + instance_index,
                resource_id: mask_resource_id,
            });
        }
    }
    Ok(())
}
