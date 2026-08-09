pub const PAGE_SIZE: u32 = 4096;
const MAX_FRAMES: usize = 1024;
const BIT_WORD_BITS: usize = 32;
const BIT_WORDS: usize = MAX_FRAMES / BIT_WORD_BITS;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PageAllocError {
    InvalidRange,
    OutOfMemory,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct FrameRange {
    pub start: u32,
    pub frame_count: u32,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct KernelReservedRanges {
    pub ram_size: u32,
    pub boot_reserved_end: u32,
    pub loader_scratch_end: u32,
    pub kernel_image_end: u32,
    pub terminal_cells_end: u32,
    pub init_kernel_stack_top: u32,
    pub init_kernel_stack_bytes: u32,
}

impl KernelReservedRanges {
    fn lower_reserved_end(self) -> u32 {
        let mut end = self.boot_reserved_end;
        if self.loader_scratch_end > end {
            end = self.loader_scratch_end;
        }
        if self.kernel_image_end > end {
            end = self.kernel_image_end;
        }
        if self.terminal_cells_end > end {
            end = self.terminal_cells_end;
        }
        end
    }

    fn init_kernel_stack_start(self) -> Result<u32, PageAllocError> {
        if self.init_kernel_stack_bytes == 0
            || self.init_kernel_stack_top % PAGE_SIZE != 0
            || self.init_kernel_stack_bytes % PAGE_SIZE != 0
            || self.init_kernel_stack_top > self.ram_size
        {
            return Err(PageAllocError::InvalidRange);
        }
        self.init_kernel_stack_top
            .checked_sub(self.init_kernel_stack_bytes)
            .ok_or(PageAllocError::InvalidRange)
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PageFrameAllocator {
    total_frames: u32,
    reserved: [u32; BIT_WORDS],
    allocated: [u32; BIT_WORDS],
}

impl PageFrameAllocator {
    pub fn new(ram_size: u32) -> Result<Self, PageAllocError> {
        if ram_size == 0 || ram_size % PAGE_SIZE != 0 {
            return Err(PageAllocError::InvalidRange);
        }
        let total_frames = ram_size / PAGE_SIZE;
        if total_frames as usize > MAX_FRAMES {
            return Err(PageAllocError::InvalidRange);
        }
        Ok(Self {
            total_frames,
            reserved: [0; BIT_WORDS],
            allocated: [0; BIT_WORDS],
        })
    }

    pub fn new_for_kernel(ranges: KernelReservedRanges) -> Result<Self, PageAllocError> {
        let mut allocator = Self::new(ranges.ram_size)?;
        let lower_reserved_end = ranges.lower_reserved_end();
        if lower_reserved_end > 0 {
            allocator.reserve_range(0, lower_reserved_end)?;
        }

        let stack_start = ranges.init_kernel_stack_start()?;
        allocator.reserve_range(stack_start, ranges.init_kernel_stack_top)?;
        Ok(allocator)
    }

    pub const fn total_frames(&self) -> u32 {
        self.total_frames
    }

    pub fn free_frames(&self) -> u32 {
        let mut free = 0;
        let mut frame = 0;
        while frame < self.total_frames {
            if !self.is_frame_reserved(frame) && !self.is_frame_allocated(frame) {
                free += 1;
            }
            frame += 1;
        }
        free
    }

    pub fn reserve_range(&mut self, start: u32, end: u32) -> Result<(), PageAllocError> {
        let Some(first_frame) = page_floor(start) else {
            return Err(PageAllocError::InvalidRange);
        };
        let Some(end_aligned) = page_ceil(end) else {
            return Err(PageAllocError::InvalidRange);
        };
        if start >= end || end_aligned > self.total_frames * PAGE_SIZE {
            return Err(PageAllocError::InvalidRange);
        }
        let last_frame_exclusive = end_aligned / PAGE_SIZE;
        let mut frame = first_frame;
        while frame < last_frame_exclusive {
            if self.is_frame_allocated(frame) {
                return Err(PageAllocError::InvalidRange);
            }
            frame += 1;
        }
        frame = first_frame;
        while frame < last_frame_exclusive {
            self.set_reserved(frame, true);
            frame += 1;
        }
        Ok(())
    }

    pub fn allocate_contiguous(&mut self, frame_count: u32) -> Result<FrameRange, PageAllocError> {
        if frame_count == 0 || frame_count > self.total_frames {
            return Err(PageAllocError::InvalidRange);
        }
        let mut run_start = 0;
        let mut run_len = 0;
        let mut frame = 0;
        while frame < self.total_frames {
            if self.is_frame_free(frame) {
                if run_len == 0 {
                    run_start = frame;
                }
                run_len += 1;
                if run_len == frame_count {
                    let mut allocated = run_start;
                    while allocated < run_start + frame_count {
                        self.set_allocated(allocated, true);
                        allocated += 1;
                    }
                    return Ok(FrameRange {
                        start: run_start * PAGE_SIZE,
                        frame_count,
                    });
                }
            } else {
                run_len = 0;
            }
            frame += 1;
        }
        Err(PageAllocError::OutOfMemory)
    }

    pub fn free_contiguous(&mut self, range: FrameRange) -> Result<(), PageAllocError> {
        if range.frame_count == 0 || range.start % PAGE_SIZE != 0 {
            return Err(PageAllocError::InvalidRange);
        }
        let first_frame = range.start / PAGE_SIZE;
        let Some(end_frame) = first_frame.checked_add(range.frame_count) else {
            return Err(PageAllocError::InvalidRange);
        };
        if end_frame > self.total_frames {
            return Err(PageAllocError::InvalidRange);
        }
        let mut frame = first_frame;
        while frame < end_frame {
            if self.is_frame_reserved(frame) || !self.is_frame_allocated(frame) {
                return Err(PageAllocError::InvalidRange);
            }
            frame += 1;
        }
        frame = first_frame;
        while frame < end_frame {
            self.set_allocated(frame, false);
            frame += 1;
        }
        Ok(())
    }

    pub fn is_frame_reserved(&self, frame: u32) -> bool {
        self.bit(self.reserved, frame)
    }

    fn is_frame_allocated(&self, frame: u32) -> bool {
        self.bit(self.allocated, frame)
    }

    fn is_frame_free(&self, frame: u32) -> bool {
        !self.is_frame_reserved(frame) && !self.is_frame_allocated(frame)
    }

    fn set_reserved(&mut self, frame: u32, value: bool) {
        set_bit(&mut self.reserved, frame, value);
    }

    fn set_allocated(&mut self, frame: u32, value: bool) {
        set_bit(&mut self.allocated, frame, value);
    }

    fn bit(&self, words: [u32; BIT_WORDS], frame: u32) -> bool {
        if frame >= self.total_frames {
            return false;
        }
        let word = frame as usize / BIT_WORD_BITS;
        let bit = frame as usize % BIT_WORD_BITS;
        words[word] & (1 << bit) != 0
    }
}

fn page_floor(address: u32) -> Option<u32> {
    Some(address / PAGE_SIZE)
}

fn page_ceil(address: u32) -> Option<u32> {
    let adjusted = address.checked_add(PAGE_SIZE - 1)?;
    Some((adjusted / PAGE_SIZE) * PAGE_SIZE)
}

fn set_bit(words: &mut [u32; BIT_WORDS], frame: u32, value: bool) {
    let word = frame as usize / BIT_WORD_BITS;
    let bit = frame as usize % BIT_WORD_BITS;
    let mask = 1 << bit;
    if value {
        words[word] |= mask;
    } else {
        words[word] &= !mask;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn allocator_initializes_frame_count_from_ram_size() {
        let allocator = PageFrameAllocator::new(0x0003_0000).expect("allocator initializes");

        assert_eq!(allocator.total_frames(), 48);
        assert_eq!(allocator.free_frames(), 48);
    }

    #[test]
    fn allocator_supports_native_compiler_four_mebibyte_ram_profile() {
        let allocator = PageFrameAllocator::new(4 * 1024 * 1024).expect("allocator initializes");

        assert_eq!(allocator.total_frames(), 1024);
        assert_eq!(allocator.free_frames(), 1024);
    }

    #[test]
    fn reserve_range_marks_all_overlapping_frames_unavailable() {
        let mut allocator = PageFrameAllocator::new(0x0001_0000).expect("allocator initializes");

        allocator
            .reserve_range(0x0000_0100, 0x0000_352d)
            .expect("range reserves");

        assert_eq!(allocator.free_frames(), 12);
        assert!(allocator.is_frame_reserved(0));
        assert!(allocator.is_frame_reserved(3));
        assert!(!allocator.is_frame_reserved(4));
    }

    #[test]
    fn allocate_contiguous_skips_reserved_frames() {
        let mut allocator = PageFrameAllocator::new(0x0001_0000).expect("allocator initializes");
        allocator
            .reserve_range(0x0000_0000, 0x0000_4000)
            .expect("range reserves");

        let range = allocator.allocate_contiguous(3).expect("frames allocate");

        assert_eq!(
            range,
            FrameRange {
                start: 0x0000_4000,
                frame_count: 3,
            }
        );
        assert_eq!(allocator.free_frames(), 9);
    }

    #[test]
    fn free_contiguous_releases_owned_frames_for_reuse() {
        let mut allocator = PageFrameAllocator::new(0x0001_0000).expect("allocator initializes");
        let first = allocator.allocate_contiguous(2).expect("first allocates");
        let second = allocator.allocate_contiguous(2).expect("second allocates");

        allocator.free_contiguous(first).expect("first frees");
        let reused = allocator.allocate_contiguous(2).expect("frames reuse");

        assert_eq!(reused, first);
        assert_ne!(reused, second);
    }

    #[test]
    fn allocate_contiguous_reports_exhaustion() {
        let mut allocator = PageFrameAllocator::new(0x0000_4000).expect("allocator initializes");
        allocator
            .reserve_range(0x0000_0000, 0x0000_3000)
            .expect("range reserves");

        assert_eq!(
            allocator.allocate_contiguous(2),
            Err(PageAllocError::OutOfMemory)
        );
    }

    #[test]
    fn kernel_reserved_ranges_mark_kernel_owned_frames_unavailable() {
        let mut allocator = PageFrameAllocator::new_for_kernel(KernelReservedRanges {
            ram_size: 0x0003_0000,
            boot_reserved_end: 0x0000_0100,
            loader_scratch_end: 0x0000_0800,
            kernel_image_end: 0x0000_8450,
            terminal_cells_end: 0x0000_352d,
            init_kernel_stack_top: 0x0003_0000,
            init_kernel_stack_bytes: PAGE_SIZE,
        })
        .expect("kernel allocator initializes");

        assert!(allocator.is_frame_reserved(0));
        assert!(allocator.is_frame_reserved(3));
        assert!(allocator.is_frame_reserved(8));
        assert!(allocator.is_frame_reserved(47));

        let range = allocator.allocate_contiguous(1).expect("frame allocates");

        assert_eq!(
            range,
            FrameRange {
                start: 0x0000_9000,
                frame_count: 1,
            }
        );
    }

    #[test]
    fn kernel_reserved_ranges_reject_invalid_init_stack() {
        assert_eq!(
            PageFrameAllocator::new_for_kernel(KernelReservedRanges {
                ram_size: 0x0001_0000,
                boot_reserved_end: 0x0000_0100,
                loader_scratch_end: 0x0000_0800,
                kernel_image_end: 0x0000_8450,
                terminal_cells_end: 0x0000_352d,
                init_kernel_stack_top: 0x0002_0000,
                init_kernel_stack_bytes: PAGE_SIZE,
            }),
            Err(PageAllocError::InvalidRange)
        );

        assert_eq!(
            PageFrameAllocator::new_for_kernel(KernelReservedRanges {
                ram_size: 0x0001_0000,
                boot_reserved_end: 0x0000_0100,
                loader_scratch_end: 0x0000_0800,
                kernel_image_end: 0x0000_8450,
                terminal_cells_end: 0x0000_352d,
                init_kernel_stack_top: 0x0000_0800,
                init_kernel_stack_bytes: PAGE_SIZE,
            }),
            Err(PageAllocError::InvalidRange)
        );
    }
}
