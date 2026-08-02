use k16_vm::retained_gpu::{
    DestinationRect, DrawCommand, DrawList, DrawListValidationError, ImageRgb565, Mask1Bpp,
    MaskInstance, MaskInstanceBuffer, MaskInstanceRecord, Resource, ResourceEntry, ResourceRef,
    SourceRect, UnresolvedDrawCommand, MASK_INSTANCE_OPAQUE_BACKGROUND, MAX_CLIP_DEPTH,
    MAX_DRAW_COMMANDS, MAX_DRAW_LIST_BYTES,
};

fn resource(id: u32, incarnation: u64, value: Resource) -> ResourceEntry {
    ResourceEntry {
        id,
        incarnation,
        revision: 1,
        value,
    }
}

fn image(id: u32, incarnation: u64, width: u16, height: u16) -> ResourceEntry {
    resource(
        id,
        incarnation,
        Resource::ImageRgb565(
            ImageRgb565::new(
                width,
                height,
                vec![0; usize::from(width) * usize::from(height)],
            )
            .expect("valid image"),
        ),
    )
}

fn mask(id: u32, incarnation: u64, width: u16, height: u16) -> ResourceEntry {
    let row_bytes = usize::from(width).div_ceil(8);
    resource(
        id,
        incarnation,
        Resource::Mask1Bpp(
            Mask1Bpp::new(width, height, vec![0; row_bytes * usize::from(height)])
                .expect("valid mask"),
        ),
    )
}

fn instance(source_x: u16, source_width: u16) -> MaskInstance {
    MaskInstance::new(MaskInstanceRecord {
        source_x,
        source_y: 0,
        source_width,
        source_height: 8,
        destination_x: 0,
        destination_y: 0,
        destination_width: 8,
        destination_height: 8,
        foreground_rgb565: 0xffff,
        background_rgb565: 0,
        flags: MASK_INSTANCE_OPAQUE_BACKGROUND,
        reserved: 0,
    })
    .expect("valid instance")
}

fn instances(id: u32, incarnation: u64, values: Vec<MaskInstance>) -> ResourceEntry {
    resource(
        id,
        incarnation,
        Resource::MaskInstanceBuffer(
            MaskInstanceBuffer::new(values.len() as u16, values).expect("valid instance buffer"),
        ),
    )
}

fn source(x: u16, y: u16, width: u16, height: u16) -> SourceRect {
    SourceRect {
        x,
        y,
        width,
        height,
    }
}

fn destination(x: i16, y: i16, width: u16, height: u16) -> DestinationRect {
    DestinationRect {
        x,
        y,
        width,
        height,
    }
}

#[test]
fn empty_draw_list_is_valid_and_keeps_its_background() {
    let list = DrawList::resolve(0x1234, Vec::new(), 8, &[]).expect("empty list");

    assert_eq!(list.background_rgb565(), 0x1234);
    assert!(list.commands().is_empty());
    assert_eq!(list.encoded_byte_len(), 8);
}

#[test]
fn draw_commands_resolve_guest_ids_to_exact_incarnations() {
    let resources = vec![
        image(1, 11, 16, 16),
        mask(2, 22, 16, 8),
        instances(3, 33, vec![instance(0, 8), instance(8, 8)]),
    ];
    let unresolved = vec![
        UnresolvedDrawCommand::PushClip {
            x: -2,
            y: -3,
            width: 20,
            height: 10,
        },
        UnresolvedDrawCommand::FillRect {
            x: 0,
            y: 0,
            width: 4,
            height: 5,
            rgb565: 0xf800,
        },
        UnresolvedDrawCommand::DrawImage {
            resource_id: 1,
            source: source(0, 0, 16, 16),
            destination: destination(1, 2, 32, 32),
        },
        UnresolvedDrawCommand::DrawMask {
            resource_id: 2,
            source: source(0, 0, 8, 8),
            destination: destination(3, 4, 8, 8),
            foreground_rgb565: 0xffff,
            background_rgb565: 0,
            opaque_background: false,
        },
        UnresolvedDrawCommand::DrawMaskInstances {
            mask_resource_id: 2,
            instance_buffer_resource_id: 3,
            first_instance: 0,
            instance_count: 2,
            translation_x: -10,
            translation_y: 10,
        },
        UnresolvedDrawCommand::PopClip,
    ];

    let list = DrawList::resolve(0, unresolved, 128, &resources).expect("valid list");

    assert!(matches!(
        list.commands()[2],
        DrawCommand::DrawImage {
            image: ResourceRef {
                id: 1,
                incarnation: 11
            },
            ..
        }
    ));
    assert!(matches!(
        list.commands()[3],
        DrawCommand::DrawMask {
            mask: ResourceRef {
                id: 2,
                incarnation: 22
            },
            ..
        }
    ));
    assert!(matches!(
        list.commands()[4],
        DrawCommand::DrawMaskInstances {
            mask: ResourceRef {
                id: 2,
                incarnation: 22
            },
            instances: ResourceRef {
                id: 3,
                incarnation: 33
            },
            ..
        }
    ));
}

#[test]
fn resource_kind_and_source_bounds_are_validated() {
    let resources = vec![image(1, 1, 8, 8), mask(2, 2, 8, 8)];

    assert_eq!(
        DrawList::resolve(
            0,
            vec![UnresolvedDrawCommand::DrawMask {
                resource_id: 1,
                source: source(0, 0, 8, 8),
                destination: destination(0, 0, 8, 8),
                foreground_rgb565: 0xffff,
                background_rgb565: 0,
                opaque_background: false,
            }],
            40,
            &resources,
        ),
        Err(DrawListValidationError::WrongResourceKind {
            command_index: 0,
            resource_id: 1,
        }),
    );
    assert_eq!(
        DrawList::resolve(
            0,
            vec![UnresolvedDrawCommand::DrawImage {
                resource_id: 1,
                source: source(4, 4, 8, 8),
                destination: destination(0, 0, 8, 8),
            }],
            36,
            &resources,
        ),
        Err(DrawListValidationError::SourceOutOfBounds {
            command_index: 0,
            resource_id: 1,
        }),
    );
}

#[test]
fn mask_instance_draw_validates_range_and_each_selected_source() {
    let resources = vec![
        mask(1, 1, 16, 8),
        instances(2, 2, vec![instance(0, 8), instance(12, 8)]),
    ];

    assert_eq!(
        DrawList::resolve(
            0,
            vec![UnresolvedDrawCommand::DrawMaskInstances {
                mask_resource_id: 1,
                instance_buffer_resource_id: 2,
                first_instance: 1,
                instance_count: 2,
                translation_x: 0,
                translation_y: 0,
            }],
            32,
            &resources,
        ),
        Err(DrawListValidationError::InstanceRangeOutOfBounds {
            command_index: 0,
            resource_id: 2,
        }),
    );
    assert_eq!(
        DrawList::resolve(
            0,
            vec![UnresolvedDrawCommand::DrawMaskInstances {
                mask_resource_id: 1,
                instance_buffer_resource_id: 2,
                first_instance: 0,
                instance_count: 2,
                translation_x: 0,
                translation_y: 0,
            }],
            32,
            &resources,
        ),
        Err(DrawListValidationError::InstanceSourceOutOfBounds {
            command_index: 0,
            instance_index: 1,
            resource_id: 1,
        }),
    );
}

#[test]
fn clip_stack_must_stay_within_bounds_and_finish_balanced() {
    assert_eq!(
        DrawList::resolve(0, vec![UnresolvedDrawCommand::PopClip], 16, &[]),
        Err(DrawListValidationError::ClipUnderflow { command_index: 0 }),
    );
    assert_eq!(
        DrawList::resolve(
            0,
            vec![UnresolvedDrawCommand::PushClip {
                x: 0,
                y: 0,
                width: 1,
                height: 1,
            }],
            24,
            &[],
        ),
        Err(DrawListValidationError::UnbalancedClips { depth: 1 }),
    );

    let too_deep = (0..=MAX_CLIP_DEPTH)
        .map(|_| UnresolvedDrawCommand::PushClip {
            x: 0,
            y: 0,
            width: 1,
            height: 1,
        })
        .collect();
    assert_eq!(
        DrawList::resolve(0, too_deep, 8 + 16 * (MAX_CLIP_DEPTH + 1), &[]),
        Err(DrawListValidationError::ClipDepthExceeded {
            command_index: MAX_CLIP_DEPTH,
        }),
    );
}

#[test]
fn geometry_background_and_list_quotas_are_enforced() {
    assert_eq!(
        DrawList::resolve(
            0,
            vec![UnresolvedDrawCommand::FillRect {
                x: 0,
                y: 0,
                width: 0,
                height: 1,
                rgb565: 0,
            }],
            28,
            &[],
        ),
        Err(DrawListValidationError::ZeroExtent { command_index: 0 }),
    );
    assert_eq!(
        DrawList::resolve(
            0,
            vec![UnresolvedDrawCommand::DrawMask {
                resource_id: 1,
                source: source(0, 0, 1, 1),
                destination: destination(0, 0, 1, 1),
                foreground_rgb565: 0xffff,
                background_rgb565: 1,
                opaque_background: false,
            }],
            40,
            &[mask(1, 1, 1, 1)],
        ),
        Err(DrawListValidationError::CutoutBackground { command_index: 0 }),
    );
    assert_eq!(
        DrawList::resolve(0, Vec::new(), MAX_DRAW_LIST_BYTES + 1, &[]),
        Err(DrawListValidationError::EncodedBytesQuota),
    );
    assert_eq!(
        DrawList::resolve(
            0,
            vec![UnresolvedDrawCommand::PopClip; MAX_DRAW_COMMANDS + 1],
            8,
            &[],
        ),
        Err(DrawListValidationError::CommandCountQuota),
    );
}
