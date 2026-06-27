use crate::k16e;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum K16ArtifactTarget {
    Bios,
    Boot,
    Kernel,
    Program,
    ProgramDynamic,
    SharedObject,
}

impl K16ArtifactTarget {
    pub const BOOT_LOAD_BASE: u32 = 0x0800;
    pub const KERNEL_LOAD_BASE: u32 = 0x4000;
    pub const PROGRAM_LOAD_BASE: u32 = 0x1_5000;
    pub const PROGRAM_STACK_TOP: u32 = 0x8_0000;
    pub const PROGRAM_INITIAL_STACK_POINTER: u32 = Self::PROGRAM_STACK_TOP - 16;
    pub const DEFAULT_MEMORY_SIZE: usize = Self::PROGRAM_STACK_TOP as usize;

    pub fn parse(value: &str) -> Result<Self, String> {
        match value {
            "bios" => Ok(Self::Bios),
            "boot" => Ok(Self::Boot),
            "kernel" => Ok(Self::Kernel),
            "program" => Ok(Self::Program),
            "program-dynamic" => Ok(Self::ProgramDynamic),
            "shared-object" => Ok(Self::SharedObject),
            _ => Err(format!(
                "unknown artifact target `{value}`; expected bios, boot, kernel, program, program-dynamic, or shared-object"
            )),
        }
    }

    pub fn base_address(self) -> u32 {
        match self {
            Self::Bios => k16_vm::computer_machine::ComputerMachine::K16_BIOS_FLASH_BASE,
            Self::Boot => Self::BOOT_LOAD_BASE,
            Self::Kernel => Self::KERNEL_LOAD_BASE,
            Self::Program => Self::PROGRAM_LOAD_BASE,
            Self::ProgramDynamic | Self::SharedObject => 0,
        }
    }

    pub fn tag(self) -> &'static str {
        match self {
            Self::Bios => "bios",
            Self::Boot => "boot",
            Self::Kernel => "kernel",
            Self::Program => "program",
            Self::ProgramDynamic => "program-dynamic",
            Self::SharedObject => "shared-object",
        }
    }

    pub fn payload_end_limit(self) -> Option<u32> {
        match self {
            Self::Program => Some(Self::PROGRAM_STACK_TOP),
            Self::ProgramDynamic | Self::SharedObject => None,
            Self::Bios | Self::Boot | Self::Kernel => None,
        }
    }

    pub fn fixed_image_abi_kind(self) -> Option<k16e::K16eAbiKind> {
        match self {
            Self::Boot => Some(k16e::K16eAbiKind::Bootloader),
            Self::Kernel => Some(k16e::K16eAbiKind::Kernel),
            Self::Program => Some(k16e::K16eAbiKind::Program),
            Self::ProgramDynamic | Self::SharedObject => None,
            Self::Bios => None,
        }
    }
}
