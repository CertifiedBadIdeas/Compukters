use k16_vm::display::{DeviceDisplayRegistry, DisplayEngine, DisplayFrameOperation, PixelFormat};
use std::fs;

fn payload_contains_rgb565(payload: &[u8], rgb565: u16) -> bool {
    let hi = (rgb565 >> 8) as u8;
    let lo = rgb565 as u8;
    payload.windows(2).any(|pair| pair == [hi, lo])
}

#[test]
fn display_dirty_tiles_use_dense_map_not_btree_set() {
    let source = fs::read_to_string("src/display.rs").expect("display source");
    let lib_source = fs::read_to_string("src/lib.rs").expect("library source");

    assert!(
        !source.contains("BTreeSet"),
        "dirty tile tracking should use a dense geometry-derived map, not BTreeSet"
    );
    assert!(
        source.contains("dirty_tile_indices"),
        "dense dirty tile tracking should keep a compact dirty-index list"
    );
    assert!(
        !source.contains("terminal_font") && !source.contains("blit_terminal_"),
        "host display engine must not own terminal fonts or glyph rasterization"
    );
    assert!(
        !lib_source.contains("pub mod generated"),
        "host VM must not expose a generated terminal font module"
    );
}

#[test]
fn active_display_docs_define_guest_owned_opaque_mono_masks() {
    let profile =
        fs::read_to_string("../../docs/abi/k16-computer-profile-v1.md").expect("computer profile");
    let changelog = fs::read_to_string("../../docs/abi/CHANGELOG.md").expect("ABI changelog");
    let profiling = fs::read_to_string("../../docs/PROFILING.md").expect("profiling guide");
    let normalized_profile = profile.split_whitespace().collect::<Vec<_>>().join(" ");

    for required in [
        "0x48 4 R/W background_color",
        "6 blit_mono_buffer",
        "MSB-first",
        "opaque",
        "row_bytes = (rect_width + 7) / 8",
        "Guest software owns font selection, glyph lookup, text layout, and mask rasterization.",
        "The VM and client do not rasterize fonts.",
    ] {
        assert!(
            normalized_profile.contains(required),
            "active computer profile must contain `{required}`"
        );
    }
    assert!(
        changelog.contains("blit_mono_buffer"),
        "ABI changelog must record the mono-mask command"
    );
    for required in [
        "monoBlits",
        "monoPayloadBytes",
        "textureUpload",
        "tilePayloadBytes",
    ] {
        assert!(
            profiling.contains(required),
            "profiling guide must document `{required}`"
        );
    }
}

#[test]
fn present_returns_dirty_tiles_and_increments_sequence() {
    let mut display = DisplayEngine::new(7, 20, 10, PixelFormat::Rgb565).unwrap();

    display.set_pixel(1, 2, 0xF800);
    let first = display.present().expect("dirty frame");

    assert_eq!(first.display_id, 7);
    assert_eq!(first.sequence, 1);
    assert_eq!(first.width, 20);
    assert_eq!(first.height, 10);
    assert_eq!(first.pixel_format, PixelFormat::Rgb565);
    assert!(!first.full_refresh);
    assert!(!first.tiles.is_empty());
    assert!(first.operations.is_empty());
    assert!(display.present().is_none());
}

#[test]
fn full_refresh_marks_whole_display() {
    let mut display = DisplayEngine::new(1, 17, 17, PixelFormat::Rgb565).unwrap();

    let frame = display.full_refresh().expect("full refresh frame");

    assert!(frame.full_refresh);
    assert_eq!(frame.sequence, 1);
    assert_eq!(frame.tiles.len(), 4);
}

#[test]
fn copy_rect_falls_back_to_dirty_tiles_when_source_depends_on_pending_tiles() {
    let mut display = DisplayEngine::new(2, 8, 4, PixelFormat::Rgb565).unwrap();
    display.set_pixel(0, 0, 0xF800);
    display.copy_rect(0, 0, 2, 2, 3, 1);
    let frame = display.present().expect("copy frame");
    let payload = frame
        .tiles
        .iter()
        .flat_map(|tile| tile.payload.iter())
        .copied()
        .collect::<Vec<_>>();

    assert!(payload_contains_rgb565(&payload, 0xF800));
    assert!(frame.operations.is_empty());
    assert!(!frame.full_refresh);
}

#[test]
fn fill_and_copy_rect_emit_operations_without_pixel_tiles() {
    let mut display = DisplayEngine::new(8, 20, 10, PixelFormat::Rgb565).unwrap();

    display.fill_rect(1, 2, 3, 4, 0x07E0);
    display.copy_rect(1, 2, 3, 4, 8, 1);
    let frame = display.present().expect("operation frame");

    assert_eq!(
        frame.operations,
        vec![
            DisplayFrameOperation::FillRect {
                x: 1,
                y: 2,
                width: 3,
                height: 4,
                rgb565: 0x07E0,
            },
            DisplayFrameOperation::CopyRect {
                src_x: 1,
                src_y: 2,
                width: 3,
                height: 4,
                dst_x: 8,
                dst_y: 1,
            },
        ],
    );
    assert!(frame.tiles.is_empty());
    assert!(!frame.full_refresh);
}

#[test]
fn blit_rgb565_rect_copies_pixel_rect_into_dirty_tiles() {
    let mut display = DisplayEngine::new(11, 20, 10, PixelFormat::Rgb565).unwrap();

    display.blit_rgb565_rect(2, 3, 3, 2, |col, row| {
        if col == 1 && row == 0 {
            0x07E0
        } else {
            0xF800
        }
    });
    let frame = display.present().expect("bulk blit frame");
    let payload = frame
        .tiles
        .iter()
        .flat_map(|tile| tile.payload.iter())
        .copied()
        .collect::<Vec<_>>();

    assert_eq!(frame.tiles.len(), 1);
    assert!(frame.operations.is_empty());
    assert!(payload_contains_rgb565(&payload, 0xF800));
    assert!(payload_contains_rgb565(&payload, 0x07E0));
    assert!(display.present().is_none());
}

#[test]
fn blit_mono_mask_emits_tight_operation_and_updates_canonical_pixels() {
    let mut display = DisplayEngine::new(12, 8, 4, PixelFormat::Rgb565).unwrap();

    display.blit_mono_mask(1, 1, 5, 2, &[0b1010_1000, 0b0101_0000], 0xffff, 0x001f);
    let frame = display.present().expect("mono frame");

    assert!(frame.tiles.is_empty());
    assert_eq!(
        frame.operations,
        vec![DisplayFrameOperation::MonoBlit {
            x: 1,
            y: 1,
            width: 5,
            height: 2,
            foreground_rgb565: 0xffff,
            background_rgb565: 0x001f,
            packed_mask: vec![0b1010_1000, 0b0101_0000],
        }],
    );

    let refresh = display.full_refresh().expect("canonical refresh");
    assert!(refresh.full_refresh);
    assert!(refresh.operations.is_empty());
    assert_eq!(refresh.tiles.len(), 1);
    let payload = &refresh.tiles[0].payload;
    let pixel = |x: usize, y: usize| {
        let offset = (y * 8 + x) * 2;
        u16::from_be_bytes([payload[offset], payload[offset + 1]])
    };
    assert_eq!(pixel(1, 1), 0xffff);
    assert_eq!(pixel(2, 1), 0x001f);
    assert_eq!(pixel(3, 1), 0xffff);
    assert_eq!(pixel(1, 2), 0x001f);
    assert_eq!(pixel(2, 2), 0xffff);
}

#[test]
fn blit_mono_mask_drops_fully_covered_dirty_tiles() {
    let mut display = DisplayEngine::new(13, 16, 16, PixelFormat::Rgb565).unwrap();

    display.set_pixel(0, 0, 0xf800);
    display.blit_mono_mask(0, 0, 16, 16, &[0xff; 32], 0x07e0, 0x0000);
    let frame = display.present().expect("mono frame");

    assert!(frame.tiles.is_empty());
    assert_eq!(frame.operations.len(), 1);
}

#[test]
fn fill_rect_after_dirty_pixels_emits_operation_and_drops_fully_covered_dirty_tile() {
    let mut display = DisplayEngine::new(9, 16, 16, PixelFormat::Rgb565).unwrap();

    display.set_pixel(0, 0, 0xF800);
    display.fill_rect(0, 0, 16, 16, 0x07E0);
    let frame = display.present().expect("operation frame");

    assert_eq!(
        frame.operations,
        vec![DisplayFrameOperation::FillRect {
            x: 0,
            y: 0,
            width: 16,
            height: 16,
            rgb565: 0x07E0,
        }],
    );
    assert!(
        frame.tiles.is_empty(),
        "covered dirty tile should not be serialized as raw pixels",
    );
}

#[test]
fn copy_rect_after_clean_source_emits_operation_and_drops_fully_covered_dirty_tile() {
    let mut display = DisplayEngine::new(10, 32, 16, PixelFormat::Rgb565).unwrap();

    display.fill_rect(16, 0, 16, 16, 0x07E0);
    display.present().expect("prime clean source");
    display.set_pixel(0, 0, 0xF800);
    display.copy_rect(16, 0, 16, 16, 0, 0);
    let frame = display.present().expect("mixed frame");

    assert_eq!(
        frame.operations,
        vec![DisplayFrameOperation::CopyRect {
            src_x: 16,
            src_y: 0,
            width: 16,
            height: 16,
            dst_x: 0,
            dst_y: 0,
        }],
    );
    assert!(
        frame.tiles.is_empty(),
        "destination fully covered by copy operation should remove the earlier dirty tile",
    );
}

#[test]
fn blit_mono_draws_foreground_and_background() {
    let mut display = DisplayEngine::new(3, 8, 4, PixelFormat::Rgb565).unwrap();

    display.blit_mono(1, 1, 3, 2, "101010", 0x07E0, Some(0x0000));
    let frame = display.present().expect("mono frame");
    let payload = frame
        .tiles
        .iter()
        .flat_map(|tile| tile.payload.iter())
        .copied()
        .collect::<Vec<_>>();

    assert!(payload_contains_rgb565(&payload, 0x07E0));
    assert!(payload_contains_rgb565(&payload, 0x0000));
}

#[test]
fn registry_attach_queues_full_refresh_and_drain_frames() {
    let mut registry = DeviceDisplayRegistry::new();

    registry.attach(9, 18, 18, PixelFormat::Rgb565).unwrap();
    let frames = registry.drain_frames();

    assert_eq!(frames.len(), 1);
    assert_eq!(frames[0].display_id, 9);
    assert!(frames[0].full_refresh);
    assert_eq!(registry.first_display_id(), Some(9));
}

#[test]
fn registry_present_queues_dirty_frame() {
    let mut registry = DeviceDisplayRegistry::new();
    registry.attach(9, 18, 18, PixelFormat::Rgb565).unwrap();
    let _ = registry.drain_frames();

    registry.fill_rect(9, 0, 0, 2, 2, 0x07E0);
    registry.present(9);
    let frames = registry.drain_frames();

    assert_eq!(frames.len(), 1);
    assert_eq!(frames[0].sequence, 2);
    assert!(!frames[0].full_refresh);
}
