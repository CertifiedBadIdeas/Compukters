use crate::font;
use crate::gpu::{self, DrawCommand, MaskInstance, TransactionBuilder};

pub const CELL_WIDTH: usize = font::CELL_WIDTH;
pub const CELL_HEIGHT: usize = font::CELL_HEIGHT;

const COLUMNS: usize = crate::memory_layout::TERMINAL_COLUMNS as usize;
const ROWS: usize = crate::memory_layout::TERMINAL_ROWS as usize;
const INSTANCE_COUNT: usize = COLUMNS * ROWS;
const FONT_MASK_ID: u32 = 1;
const TERMINAL_INSTANCES_ID: u32 = 2;
const BIOS_FONT_MASK_ID: u32 = 0xffff_ff01;
const BIOS_INSTANCES_ID: u32 = 0xffff_ff02;
const FONT_ATLAS_WIDTH: u16 = 128;
const FONT_ATLAS_HEIGHT: u16 = 128;
const FONT_ATLAS_BYTES: usize = 2_048;
const MAX_DIRTY_RANGES: usize = 64;
const PACKET_SCRATCH_BYTES: usize = 65_536;
const FOREGROUND: u16 = 0xffff;
const BACKGROUND: u16 = 0x0000;

const EMPTY_RANGE: DirtyRange = DirtyRange { start: 0, count: 0 };

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DirtyRange {
    pub start: u16,
    pub count: u16,
}

pub struct FlushPlan<'a> {
    ranges: &'a [DirtyRange],
    row_head: usize,
    replaces_draw_list: bool,
}

impl FlushPlan<'_> {
    pub fn instance_patch_count(&self) -> usize {
        self.ranges.len()
    }

    pub fn replaces_draw_list_after_scroll(&self) -> bool {
        self.replaces_draw_list
    }

    pub fn draw_instance_ranges(&self) -> usize {
        if self.row_head == 0 {
            1
        } else {
            2
        }
    }

    pub fn transaction_count(&self) -> usize {
        usize::from(!self.ranges.is_empty() || self.replaces_draw_list)
    }
}

pub struct TerminalRenderState {
    instances: [MaskInstance; INSTANCE_COUNT],
    dirty_ranges: [DirtyRange; MAX_DIRTY_RANGES],
    dirty_range_count: usize,
    row_head: usize,
    committed_row_head: usize,
    expected_sequence: u64,
}

impl TerminalRenderState {
    pub const fn new() -> Self {
        let mut instances = [blank_instance(0, 0); INSTANCE_COUNT];
        let mut row = 0;
        while row < ROWS {
            let mut column = 0;
            while column < COLUMNS {
                instances[row * COLUMNS + column] = blank_instance(column, row);
                column += 1;
            }
            row += 1;
        }
        let mut dirty_ranges = [EMPTY_RANGE; MAX_DIRTY_RANGES];
        dirty_ranges[0] = DirtyRange {
            start: 0,
            count: INSTANCE_COUNT as u16,
        };
        Self {
            instances,
            dirty_ranges,
            dirty_range_count: 1,
            row_head: 0,
            committed_row_head: 0,
            expected_sequence: 0,
        }
    }

    pub fn instance_capacity(&self) -> usize {
        self.instances.len()
    }

    pub fn dirty_ranges(&self) -> &[DirtyRange] {
        &self.dirty_ranges[..self.dirty_range_count]
    }

    pub fn flush_plan(&self) -> FlushPlan<'_> {
        FlushPlan {
            ranges: self.dirty_ranges(),
            row_head: self.row_head,
            replaces_draw_list: self.row_head != self.committed_row_head,
        }
    }

    pub fn clear_pending(&mut self) {
        self.dirty_range_count = 0;
        self.committed_row_head = self.row_head;
    }

    pub fn set_cell(&mut self, column: usize, row: usize, byte: u8) {
        if column >= COLUMNS || row >= ROWS {
            return;
        }
        let physical_row = (self.row_head + row) % ROWS;
        let index = physical_row * COLUMNS + column;
        self.instances[index] = glyph_instance(column, physical_row, byte);
        self.mark_dirty(index, 1);
    }

    pub fn scroll_up(&mut self) {
        let reclaimed_row = self.row_head;
        self.row_head = (self.row_head + 1) % ROWS;
        for column in 0..COLUMNS {
            self.instances[reclaimed_row * COLUMNS + column] =
                blank_instance(column, reclaimed_row);
        }
        self.mark_dirty(reclaimed_row * COLUMNS, COLUMNS);
    }

    pub fn reset(&mut self) {
        self.row_head = 0;
        for row in 0..ROWS {
            for column in 0..COLUMNS {
                self.instances[row * COLUMNS + column] = blank_instance(column, row);
            }
        }
        self.dirty_ranges[0] = DirtyRange {
            start: 0,
            count: INSTANCE_COUNT as u16,
        };
        self.dirty_range_count = 1;
    }

    fn records(&self, range: DirtyRange) -> &[MaskInstance] {
        let start = usize::from(range.start);
        &self.instances[start..start + usize::from(range.count)]
    }

    fn draw_commands(&self) -> ([DrawCommand; 2], usize) {
        let first_count = (ROWS - self.row_head) * COLUMNS;
        let first = DrawCommand::MaskInstances {
            mask_resource_id: FONT_MASK_ID,
            instance_buffer_resource_id: TERMINAL_INSTANCES_ID,
            first_instance: (self.row_head * COLUMNS) as u16,
            instance_count: first_count as u16,
            translation_x: 0,
            translation_y: -((self.row_head * CELL_HEIGHT) as i16),
        };
        let second = DrawCommand::MaskInstances {
            mask_resource_id: FONT_MASK_ID,
            instance_buffer_resource_id: TERMINAL_INSTANCES_ID,
            first_instance: 0,
            instance_count: (self.row_head * COLUMNS) as u16,
            translation_x: 0,
            translation_y: ((ROWS - self.row_head) * CELL_HEIGHT) as i16,
        };
        ([first, second], if self.row_head == 0 { 1 } else { 2 })
    }

    fn mark_dirty(&mut self, start: usize, count: usize) {
        let mut merged_start = start;
        let mut merged_end = start + count;
        let mut index = 0;
        while index < self.dirty_range_count {
            let current = self.dirty_ranges[index];
            let current_start = usize::from(current.start);
            let current_end = current_start + usize::from(current.count);
            if current_end < merged_start {
                index += 1;
                continue;
            }
            if merged_end < current_start {
                break;
            }
            merged_start = merged_start.min(current_start);
            merged_end = merged_end.max(current_end);
            self.remove_dirty_range(index);
        }
        if self.dirty_range_count == MAX_DIRTY_RANGES {
            self.dirty_ranges[0] = DirtyRange {
                start: 0,
                count: INSTANCE_COUNT as u16,
            };
            self.dirty_range_count = 1;
            return;
        }
        let mut tail = self.dirty_range_count;
        while tail > index {
            self.dirty_ranges[tail] = self.dirty_ranges[tail - 1];
            tail -= 1;
        }
        self.dirty_ranges[index] = DirtyRange {
            start: merged_start as u16,
            count: (merged_end - merged_start) as u16,
        };
        self.dirty_range_count += 1;
    }

    fn remove_dirty_range(&mut self, index: usize) {
        let mut cursor = index;
        while cursor + 1 < self.dirty_range_count {
            self.dirty_ranges[cursor] = self.dirty_ranges[cursor + 1];
            cursor += 1;
        }
        self.dirty_range_count -= 1;
    }
}

static mut STATE: TerminalRenderState = TerminalRenderState::new();
static mut FONT_ATLAS: [u8; FONT_ATLAS_BYTES] = [0; FONT_ATLAS_BYTES];
static mut PACKET_SCRATCH: [u8; PACKET_SCRATCH_BYTES] = [0; PACKET_SCRATCH_BYTES];

pub fn init() {
    unsafe {
        let state = &mut *core::ptr::addr_of_mut!(STATE);
        *state = TerminalRenderState::new();
        let atlas = &mut *core::ptr::addr_of_mut!(FONT_ATLAS);
        build_font_atlas(atlas);
        let scratch = &mut *core::ptr::addr_of_mut!(PACKET_SCRATCH);
        let base = gpu::committed_sequence();
        let mut builder = TransactionBuilder::new(scratch, base).unwrap();
        builder
            .create_mask(FONT_MASK_ID, FONT_ATLAS_WIDTH, FONT_ATLAS_HEIGHT, atlas)
            .unwrap();
        builder
            .create_mask_instance_buffer(TERMINAL_INSTANCES_ID, &state.instances)
            .unwrap();
        let (commands, command_count) = state.draw_commands();
        builder
            .replace_draw_list(BACKGROUND, &commands[..command_count])
            .unwrap();
        let commit = gpu::submit(builder.finish().unwrap()).unwrap();
        state.expected_sequence = commit.sequence;
        state.clear_pending();

        let mut cleanup = TransactionBuilder::new(scratch, state.expected_sequence).unwrap();
        cleanup.drop_resource(BIOS_FONT_MASK_ID).unwrap();
        cleanup.drop_resource(BIOS_INSTANCES_ID).unwrap();
        let commit = gpu::submit(cleanup.finish().unwrap()).unwrap();
        state.expected_sequence = commit.sequence;
    }
}

pub fn reset() {
    unsafe { (&mut *core::ptr::addr_of_mut!(STATE)).reset() }
}

pub fn set_cell(column: usize, row: usize, byte: u8) {
    unsafe { (&mut *core::ptr::addr_of_mut!(STATE)).set_cell(column, row, byte) }
}

pub fn scroll_up() {
    unsafe { (&mut *core::ptr::addr_of_mut!(STATE)).scroll_up() }
}

pub fn flush() {
    unsafe {
        let state = &mut *core::ptr::addr_of_mut!(STATE);
        let plan = state.flush_plan();
        if plan.transaction_count() == 0 {
            return;
        }
        let scratch = &mut *core::ptr::addr_of_mut!(PACKET_SCRATCH);
        let mut builder = TransactionBuilder::new(scratch, state.expected_sequence).unwrap();
        for range in state.dirty_ranges() {
            builder
                .patch_mask_instances(TERMINAL_INSTANCES_ID, range.start, state.records(*range))
                .unwrap();
        }
        if plan.replaces_draw_list_after_scroll() {
            let (commands, command_count) = state.draw_commands();
            builder
                .replace_draw_list(BACKGROUND, &commands[..command_count])
                .unwrap();
        }
        let commit = gpu::submit(builder.finish().unwrap()).unwrap();
        state.expected_sequence = commit.sequence;
        state.clear_pending();
    }
}

fn build_font_atlas(atlas: &mut [u8; FONT_ATLAS_BYTES]) {
    atlas.fill(0);
    for byte in 0u8..=127 {
        let cell_x = usize::from(byte % 16);
        let cell_y = usize::from(byte / 16);
        let glyph = font::glyph(byte);
        for row in 0..font::GLYPH_HEIGHT {
            let atlas_row = cell_y * 8 + row;
            atlas[atlas_row * 16 + cell_x] = glyph[row] << 3;
        }
    }
}

const fn blank_instance(column: usize, physical_row: usize) -> MaskInstance {
    glyph_instance(column, physical_row, b' ')
}

const fn glyph_instance(column: usize, physical_row: usize, byte: u8) -> MaskInstance {
    MaskInstance {
        source_x: (byte % 16) as u16 * 8,
        source_y: (byte / 16) as u16 * 8,
        source_width: font::GLYPH_WIDTH as u16,
        source_height: font::CELL_HEIGHT as u16,
        destination_x: (column * CELL_WIDTH) as i16,
        destination_y: (physical_row * CELL_HEIGHT) as i16,
        destination_width: CELL_WIDTH as u16,
        destination_height: CELL_HEIGHT as u16,
        foreground_rgb565: FOREGROUND,
        background_rgb565: BACKGROUND,
        flags: 1,
    }
}

#[cfg(test)]
mod tests {
    use super::{
        DirtyRange, TerminalRenderState, BACKGROUND, PACKET_SCRATCH_BYTES, TERMINAL_INSTANCES_ID,
    };
    use crate::gpu::TransactionBuilder;

    #[test]
    fn retained_state_merges_cell_and_row_dirty_ranges() {
        let mut state = TerminalRenderState::new();
        state.clear_pending();

        state.set_cell(17, 0, b'A');
        assert_eq!(state.instance_capacity(), 1_600);
        assert_eq!(
            state.dirty_ranges(),
            &[DirtyRange {
                start: 17,
                count: 1
            }]
        );

        state.set_cell(18, 0, b'B');
        assert_eq!(
            state.dirty_ranges(),
            &[DirtyRange {
                start: 17,
                count: 2
            }]
        );
        assert_eq!(state.flush_plan().instance_patch_count(), 1);

        state.clear_pending();
        for column in 0..64 {
            state.set_cell(column, 1, b'X');
        }
        assert_eq!(
            state.dirty_ranges(),
            &[DirtyRange {
                start: 64,
                count: 64
            }]
        );
    }

    #[test]
    fn circular_scroll_uses_at_most_two_draw_ranges_and_one_final_flush() {
        let mut state = TerminalRenderState::new();
        state.clear_pending();

        state.scroll_up();
        let plan = state.flush_plan();
        assert!(plan.replaces_draw_list_after_scroll());
        assert!(plan.draw_instance_ranges() <= 2);
        assert_eq!(plan.instance_patch_count(), 1);

        let mut wrapped = TerminalRenderState::new();
        wrapped.clear_pending();
        for _ in 0..25 {
            wrapped.scroll_up();
        }
        assert_eq!(wrapped.flush_plan().draw_instance_ranges(), 1);
        assert_eq!(wrapped.flush_plan().transaction_count(), 1);
    }

    #[test]
    fn output_burst_larger_than_the_screen_fits_one_bounded_transaction() {
        let mut state = TerminalRenderState::new();
        state.clear_pending();
        let mut column = 0;
        let mut row = 0;

        for index in 0..2_000 {
            state.set_cell(column, row, b'A' + (index % 26) as u8);
            column += 1;
            if column == 64 {
                column = 0;
                row += 1;
                if row == 25 {
                    state.scroll_up();
                    row -= 1;
                }
            }
        }

        let plan = state.flush_plan();
        assert_eq!(plan.transaction_count(), 1);
        assert!(plan.instance_patch_count() <= 64);
        assert!(plan.draw_instance_ranges() <= 2);

        let mut scratch = [0; PACKET_SCRATCH_BYTES];
        let mut builder = TransactionBuilder::new(&mut scratch, 0).unwrap();
        for range in state.dirty_ranges() {
            builder
                .patch_mask_instances(TERMINAL_INSTANCES_ID, range.start, state.records(*range))
                .unwrap();
        }
        let (commands, command_count) = state.draw_commands();
        builder
            .replace_draw_list(BACKGROUND, &commands[..command_count])
            .unwrap();

        assert!(builder.finish().unwrap().len() <= PACKET_SCRATCH_BYTES);
    }
}
