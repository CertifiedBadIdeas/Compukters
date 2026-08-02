use k16_vm::retained_gpu::{
    ImageRgb565, Mask1Bpp, MaskInstance, MaskInstanceBuffer, MaskInstanceRecord, Resource,
    ResourceValidationError, MASK_INSTANCE_OPAQUE_BACKGROUND,
};

fn opaque_instance(source_x: u16, foreground_rgb565: u16) -> MaskInstance {
    MaskInstance::new(MaskInstanceRecord {
        source_x,
        source_y: 0,
        source_width: 8,
        source_height: 8,
        destination_x: 0,
        destination_y: 0,
        destination_width: 8,
        destination_height: 8,
        foreground_rgb565,
        background_rgb565: 0,
        flags: MASK_INSTANCE_OPAQUE_BACKGROUND,
        reserved: 0,
    })
    .expect("valid instance")
}

#[test]
fn image_requires_complete_non_empty_checked_payload() {
    assert_eq!(
        ImageRgb565::new(0, 1, vec![]),
        Err(ResourceValidationError::ZeroExtent),
    );
    assert_eq!(
        ImageRgb565::new(2, 2, vec![0; 3]),
        Err(ResourceValidationError::PayloadLength {
            expected: 4,
            actual: 3,
        }),
    );

    let image = ImageRgb565::new(2, 2, vec![1, 2, 3, 4]).expect("valid image");
    assert_eq!(image.width(), 2);
    assert_eq!(image.height(), 2);
    assert_eq!(image.pixels(), &[1, 2, 3, 4]);
    assert_eq!(Resource::ImageRgb565(image).payload_bytes(), 8);
}

#[test]
fn mask_rejects_non_zero_unused_row_bits() {
    assert_eq!(
        Mask1Bpp::new(10, 1, vec![0xff, 0xc1]),
        Err(ResourceValidationError::UnusedMaskBits {
            row: 0,
            byte: 0xc1,
            allowed_mask: 0xc0,
        }),
    );

    let mask = Mask1Bpp::new(10, 2, vec![0xff, 0xc0, 0x00, 0x00]).expect("valid mask");
    assert_eq!(mask.row_bytes(), 2);
    assert_eq!(Resource::Mask1Bpp(mask).payload_bytes(), 4);
}

#[test]
fn mask_patch_can_start_mid_byte_without_touching_neighbor_bits() {
    let mut mask = Mask1Bpp::new(10, 1, vec![0xaa, 0xc0]).expect("valid mask");

    mask.patch_rect(3, 0, 5, 1, &[0x68])
        .expect("valid mid-byte patch");

    assert_eq!(mask.rows(), &[0xad, 0xc0]);
}

#[test]
fn image_patch_replaces_only_the_requested_rectangle() {
    let mut image = ImageRgb565::new(3, 2, vec![1, 2, 3, 4, 5, 6]).expect("valid image");

    image
        .patch_rect(1, 0, 2, 2, &[20, 30, 50, 60])
        .expect("valid image patch");

    assert_eq!(image.pixels(), &[1, 20, 30, 4, 50, 60]);
}

#[test]
fn mask_instance_validation_matches_the_wire_contract() {
    let base = MaskInstanceRecord {
        source_x: 0,
        source_y: 0,
        source_width: 8,
        source_height: 8,
        destination_x: 0,
        destination_y: 0,
        destination_width: 8,
        destination_height: 8,
        foreground_rgb565: 0xffff,
        background_rgb565: 0,
        flags: 0,
        reserved: 0,
    };

    assert_eq!(
        MaskInstance::new(MaskInstanceRecord {
            source_width: 0,
            ..base
        }),
        Err(ResourceValidationError::ZeroExtent),
    );
    assert_eq!(
        MaskInstance::new(MaskInstanceRecord { flags: 2, ..base }),
        Err(ResourceValidationError::UnknownInstanceFlags(2)),
    );
    assert_eq!(
        MaskInstance::new(MaskInstanceRecord {
            reserved: 1,
            ..base
        }),
        Err(ResourceValidationError::NonZeroReserved(1)),
    );
    assert_eq!(
        MaskInstance::new(MaskInstanceRecord {
            background_rgb565: 0x1234,
            ..base
        }),
        Err(ResourceValidationError::CutoutBackground(0x1234)),
    );
}

#[test]
fn instance_buffer_patch_replaces_only_the_requested_entries() {
    let first = opaque_instance(0, 1);
    let second = opaque_instance(8, 2);
    let third = opaque_instance(16, 3);
    let replacement = opaque_instance(24, 9);
    let mut instances =
        MaskInstanceBuffer::new(3, vec![first, second, third]).expect("valid buffer");

    instances
        .patch(1, &[replacement])
        .expect("valid instance patch");

    assert_eq!(instances.instances(), &[first, replacement, third]);
    assert_eq!(Resource::MaskInstanceBuffer(instances).payload_bytes(), 72,);
}

#[test]
fn patches_reject_zero_extents_and_out_of_bounds_ranges() {
    let mut image = ImageRgb565::new(2, 2, vec![0; 4]).expect("valid image");
    assert_eq!(
        image.patch_rect(0, 0, 0, 1, &[]),
        Err(ResourceValidationError::ZeroExtent),
    );
    assert_eq!(
        image.patch_rect(1, 1, 2, 1, &[0; 2]),
        Err(ResourceValidationError::OutOfBounds),
    );

    let instance = opaque_instance(0, 1);
    let mut instances = MaskInstanceBuffer::new(1, vec![instance]).expect("valid instance buffer");
    assert_eq!(
        instances.patch(1, &[instance]),
        Err(ResourceValidationError::OutOfBounds),
    );
    assert_eq!(
        instances.patch(0, &[]),
        Err(ResourceValidationError::ZeroExtent),
    );
}
