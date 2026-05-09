use ckl_vm::display::{DeviceDisplayRegistry, DisplayEngine, PixelFormat};

fn payload_contains_rgb565(payload: &[u8], rgb565: u16) -> bool {
    let hi = (rgb565 >> 8) as u8;
    let lo = rgb565 as u8;
    payload.windows(2).any(|pair| pair == [hi, lo])
}

#[test]
fn present_returns_dirty_tiles_and_increments_sequence() {
    let mut display = DisplayEngine::new(7, 20, 10, PixelFormat::Rgb565).unwrap();

    display.fill_rect(1, 2, 3, 4, 0xF800);
    let first = display.present().expect("dirty frame");

    assert_eq!(first.display_id, 7);
    assert_eq!(first.sequence, 1);
    assert_eq!(first.width, 20);
    assert_eq!(first.height, 10);
    assert_eq!(first.pixel_format, PixelFormat::Rgb565);
    assert!(!first.full_refresh);
    assert!(!first.tiles.is_empty());
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
fn copy_rect_copies_pixels_and_marks_destination_dirty() {
    let mut display = DisplayEngine::new(2, 8, 4, PixelFormat::Rgb565).unwrap();
    display.fill_rect(0, 0, 8, 4, 0x0000);
    display.fill_rect(0, 0, 2, 2, 0xF800);
    let _ = display.present();

    display.copy_rect(0, 0, 2, 2, 3, 1);
    let frame = display.present().expect("copy frame");
    let payload = frame
        .tiles
        .iter()
        .flat_map(|tile| tile.payload.iter())
        .copied()
        .collect::<Vec<_>>();

    assert!(payload_contains_rgb565(&payload, 0xF800));
    assert!(!frame.full_refresh);
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
fn blit_mono5x7_text_draws_glyph_run() {
    let mut display = DisplayEngine::new(4, 18, 9, PixelFormat::Rgb565).unwrap();

    display.blit_mono5x7_text(0, 1, "AB", 0x07E0, None);
    let frame = display.present().expect("text frame");
    let payload = frame
        .tiles
        .iter()
        .flat_map(|tile| tile.payload.iter())
        .copied()
        .collect::<Vec<_>>();

    assert!(payload_contains_rgb565(&payload, 0x07E0));
}

#[test]
fn text_run_supports_digits_lowercase_and_punctuation() {
    fn single_text_payload(text: &str) -> Vec<u8> {
        let mut display = DisplayEngine::new(6, 18, 9, PixelFormat::Rgb565).unwrap();
        display.blit_mono5x7_text(0, 1, text, 0x07E0, Some(0x0000));
        display
            .present()
            .expect("text frame")
            .tiles
            .iter()
            .flat_map(|tile| tile.payload.iter())
            .copied()
            .collect::<Vec<_>>()
    }

    assert_eq!(single_text_payload("a"), single_text_payload("A"));
    assert_eq!(single_text_payload("x"), single_text_payload("X"));
    assert_ne!(single_text_payload("1"), single_text_payload("@"));
    assert_ne!(single_text_payload("-"), single_text_payload("@"));
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
