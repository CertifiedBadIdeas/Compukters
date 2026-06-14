pub const K16_VM_PAGE_SIZE: u32 = 4096;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MmuAccess {
    Fetch,
    Load,
    Store,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MmuPrivilege {
    Kernel,
    User,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MmuFaultKind {
    NotPresent,
    Permission,
    InvalidMapping,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MmuFault {
    pub address: u32,
    pub access: MmuAccess,
    pub kind: MmuFaultKind,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MmuMapFlags(u32);

impl MmuMapFlags {
    pub const NONE: Self = Self(0);
    pub const USER_ACCESSIBLE: Self = Self(1 << 0);
    pub const WRITABLE: Self = Self(1 << 1);
    pub const EXECUTABLE: Self = Self(1 << 2);

    fn contains(self, other: Self) -> bool {
        self.0 & other.0 == other.0
    }
}

impl std::ops::BitOr for MmuMapFlags {
    type Output = Self;

    fn bitor(self, rhs: Self) -> Self::Output {
        Self(self.0 | rhs.0)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct Mapping {
    virtual_start: u32,
    physical_start: u32,
    page_count: u32,
    flags: MmuMapFlags,
}

impl Mapping {
    fn virtual_end(self) -> Option<u32> {
        byte_len(self.page_count).and_then(|len| self.virtual_start.checked_add(len))
    }

    fn contains(self, address: u32) -> bool {
        let Some(end) = self.virtual_end() else {
            return false;
        };
        address >= self.virtual_start && address < end
    }

    fn overlaps(self, virtual_start: u32, virtual_end: u32) -> bool {
        let Some(existing_end) = self.virtual_end() else {
            return true;
        };
        virtual_start < existing_end && self.virtual_start < virtual_end
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MmuAddressSpace {
    ram_size: u32,
    mappings: Vec<Mapping>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct MmuAddressSpaceId(u32);

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MmuAddressSpaces {
    next_id: u32,
    spaces: Vec<(MmuAddressSpaceId, MmuAddressSpace)>,
}

impl MmuAddressSpaces {
    pub fn new() -> Self {
        Self {
            next_id: 1,
            spaces: Vec::new(),
        }
    }

    pub fn create(&mut self, ram_size: u32) -> Result<MmuAddressSpaceId, MmuFault> {
        let id = MmuAddressSpaceId(self.next_id);
        self.next_id = self.next_id.checked_add(1).ok_or(MmuFault {
            address: 0,
            access: MmuAccess::Load,
            kind: MmuFaultKind::InvalidMapping,
        })?;
        self.spaces.push((id, MmuAddressSpace::new(ram_size)));
        Ok(id)
    }

    pub fn get(&self, id: MmuAddressSpaceId) -> Option<&MmuAddressSpace> {
        self.spaces
            .iter()
            .find_map(|(space_id, space)| (*space_id == id).then_some(space))
    }

    pub fn get_mut(&mut self, id: MmuAddressSpaceId) -> Option<&mut MmuAddressSpace> {
        self.spaces
            .iter_mut()
            .find_map(|(space_id, space)| (*space_id == id).then_some(space))
    }

    pub fn destroy(&mut self, id: MmuAddressSpaceId) -> bool {
        let Some(index) = self
            .spaces
            .iter()
            .position(|(space_id, _space)| *space_id == id)
        else {
            return false;
        };
        self.spaces.remove(index);
        true
    }
}

impl Default for MmuAddressSpaces {
    fn default() -> Self {
        Self::new()
    }
}

impl MmuAddressSpace {
    pub fn new(ram_size: u32) -> Self {
        Self {
            ram_size,
            mappings: Vec::new(),
        }
    }

    pub fn map_pages(
        &mut self,
        virtual_start: u32,
        physical_start: u32,
        page_count: u32,
        flags: MmuMapFlags,
    ) -> Result<(), MmuFault> {
        let mapping = self.validate_mapping(virtual_start, physical_start, page_count, flags)?;
        self.mappings.push(mapping);
        Ok(())
    }

    pub fn protect_pages(
        &mut self,
        virtual_start: u32,
        page_count: u32,
        flags: MmuMapFlags,
    ) -> Result<(), MmuFault> {
        self.replace_mapped_range(virtual_start, page_count, Some(flags))
    }

    pub fn unmap_pages(&mut self, virtual_start: u32, page_count: u32) -> Result<(), MmuFault> {
        self.replace_mapped_range(virtual_start, page_count, None)
    }

    pub fn translate(
        &self,
        address: u32,
        access: MmuAccess,
        privilege: MmuPrivilege,
    ) -> Result<u32, MmuFault> {
        let mapping = self
            .mappings
            .iter()
            .copied()
            .find(|mapping| mapping.contains(address))
            .ok_or(MmuFault {
                address,
                access,
                kind: MmuFaultKind::NotPresent,
            })?;
        if matches!(privilege, MmuPrivilege::User)
            && !mapping.flags.contains(MmuMapFlags::USER_ACCESSIBLE)
            || matches!(access, MmuAccess::Fetch)
                && !mapping.flags.contains(MmuMapFlags::EXECUTABLE)
            || matches!(access, MmuAccess::Store) && !mapping.flags.contains(MmuMapFlags::WRITABLE)
        {
            return Err(MmuFault {
                address,
                access,
                kind: MmuFaultKind::Permission,
            });
        }
        let offset = address - mapping.virtual_start;
        Ok(mapping.physical_start + offset)
    }

    fn replace_mapped_range(
        &mut self,
        virtual_start: u32,
        page_count: u32,
        replacement_flags: Option<MmuMapFlags>,
    ) -> Result<(), MmuFault> {
        let range = PageRange::new(virtual_start, page_count)?;
        let index = self.containing_mapping_index(range)?;
        let mapping = self.mappings.remove(index);
        if mapping.virtual_start < range.start {
            let left_pages = (range.start - mapping.virtual_start) / K16_VM_PAGE_SIZE;
            self.mappings.push(Mapping {
                virtual_start: mapping.virtual_start,
                physical_start: mapping.physical_start,
                page_count: left_pages,
                flags: mapping.flags,
            });
        }
        if let Some(flags) = replacement_flags {
            self.mappings.push(Mapping {
                virtual_start: range.start,
                physical_start: mapping.physical_start + (range.start - mapping.virtual_start),
                page_count,
                flags,
            });
        }
        let mapping_end = mapping
            .virtual_end()
            .ok_or_else(|| invalid_mapping_fault(virtual_start))?;
        if range.end < mapping_end {
            let right_pages = (mapping_end - range.end) / K16_VM_PAGE_SIZE;
            self.mappings.push(Mapping {
                virtual_start: range.end,
                physical_start: mapping.physical_start + (range.end - mapping.virtual_start),
                page_count: right_pages,
                flags: mapping.flags,
            });
        }
        Ok(())
    }

    fn containing_mapping_index(&self, range: PageRange) -> Result<usize, MmuFault> {
        self.mappings
            .iter()
            .position(|mapping| {
                mapping.virtual_start <= range.start
                    && mapping
                        .virtual_end()
                        .is_some_and(|mapping_end| range.end <= mapping_end)
            })
            .ok_or_else(|| invalid_mapping_fault(range.start))
    }

    fn validate_mapping(
        &self,
        virtual_start: u32,
        physical_start: u32,
        page_count: u32,
        flags: MmuMapFlags,
    ) -> Result<Mapping, MmuFault> {
        let fault = invalid_mapping_fault(virtual_start);
        if page_count == 0 || !is_page_aligned(virtual_start) || !is_page_aligned(physical_start) {
            return Err(fault);
        }
        let Some(virtual_end) = byte_len(page_count).and_then(|len| virtual_start.checked_add(len))
        else {
            return Err(fault);
        };
        let Some(physical_end) =
            byte_len(page_count).and_then(|len| physical_start.checked_add(len))
        else {
            return Err(fault);
        };
        if physical_end > self.ram_size {
            return Err(fault);
        }
        if self
            .mappings
            .iter()
            .any(|mapping| mapping.overlaps(virtual_start, virtual_end))
        {
            return Err(fault);
        }
        Ok(Mapping {
            virtual_start,
            physical_start,
            page_count,
            flags,
        })
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct PageRange {
    start: u32,
    end: u32,
}

impl PageRange {
    fn new(start: u32, page_count: u32) -> Result<Self, MmuFault> {
        if page_count == 0 || !is_page_aligned(start) {
            return Err(invalid_mapping_fault(start));
        }
        let end = byte_len(page_count)
            .and_then(|len| start.checked_add(len))
            .ok_or_else(|| invalid_mapping_fault(start))?;
        Ok(Self { start, end })
    }
}

fn is_page_aligned(value: u32) -> bool {
    value % K16_VM_PAGE_SIZE == 0
}

fn byte_len(page_count: u32) -> Option<u32> {
    page_count.checked_mul(K16_VM_PAGE_SIZE)
}

fn invalid_mapping_fault(address: u32) -> MmuFault {
    MmuFault {
        address,
        access: MmuAccess::Load,
        kind: MmuFaultKind::InvalidMapping,
    }
}
