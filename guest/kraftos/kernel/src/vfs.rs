use core::cell::UnsafeCell;
use core::mem::MaybeUninit;

use crate::kfs::device::KfsDevice;
use crate::kfs::error::StorageError;
use crate::kfs::volume::KfsVolume;

const ROOT_PARTITION: &[u8; 4] = b"ROOT";

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum VolumeId {
    Root,
    Sdk,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Route<'a> {
    Root(&'a [u8]),
    Sdk(&'a [u8]),
}

impl<'a> Route<'a> {
    pub const fn volume(self) -> VolumeId {
        match self {
            Self::Root(_) => VolumeId::Root,
            Self::Sdk(_) => VolumeId::Sdk,
        }
    }

    pub const fn is_sdk(self) -> bool {
        matches!(self, Self::Sdk(_))
    }

    pub fn path(self) -> &'a [u8] {
        match self {
            Self::Root(path) | Self::Sdk(path) if path.is_empty() => b"/",
            Self::Root(path) | Self::Sdk(path) => path,
        }
    }
}

pub fn route(path: &[u8], sdk_attached: bool) -> Route<'_> {
    if sdk_attached && path == b"/sdk" {
        Route::Sdk(&[])
    } else if sdk_attached && path.starts_with(b"/sdk/") {
        Route::Sdk(&path[4..])
    } else {
        Route::Root(path)
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum VfsInitError {
    AlreadyInitialized,
    InvalidStorage1Profile,
    InvalidSdkFilesystem(StorageError),
}

pub struct KernelVfs {
    root: KfsVolume,
    sdk: Option<KfsVolume>,
}

impl KernelVfs {
    pub const fn root_only() -> Self {
        Self {
            root: KfsVolume::new(KfsDevice::storage0(), false),
            sdk: None,
        }
    }

    pub const fn has_sdk(&self) -> bool {
        self.sdk.is_some()
    }

    pub fn route<'a>(&self, path: &'a [u8]) -> Route<'a> {
        route(path, self.has_sdk())
    }

    pub fn volume_mut(&mut self, id: VolumeId) -> Option<&mut KfsVolume> {
        match id {
            VolumeId::Root => Some(&mut self.root),
            VolumeId::Sdk => self.sdk.as_mut(),
        }
    }

    unsafe fn discover() -> Result<Self, VfsInitError> {
        let mut vfs = Self::root_only();
        let storage1 = unsafe {
            k16_abi::computer::profile::find_hardware_entry(
                k16_abi::computer::hardware_id::STORAGE1,
            )
        };
        let Some(entry) = storage1 else {
            return Ok(vfs);
        };
        if !valid_storage1_entry(entry) {
            return Err(VfsInitError::InvalidStorage1Profile);
        }

        let mut sdk = KfsVolume::new(KfsDevice::storage1(), true);
        let mounted = unsafe {
            crate::kfs::mount::mount_root_partition_superblock(&mut sdk, ROOT_PARTITION)
                .map_err(VfsInitError::InvalidSdkFilesystem)?
        };
        sdk.mounted_partition_type = Some(*ROOT_PARTITION);
        sdk.mounted = Some(mounted);
        vfs.sdk = Some(sdk);
        Ok(vfs)
    }
}

fn valid_storage1_entry(entry: k16_abi::computer::profile::HardwareEntry) -> bool {
    entry.mmio_base == k16_abi::computer::storage1::BASE && entry.mmio_size == 0x100
}

struct KernelVfsCell {
    initialized: UnsafeCell<bool>,
    value: UnsafeCell<MaybeUninit<KernelVfs>>,
}

unsafe impl Sync for KernelVfsCell {}

impl KernelVfsCell {
    const fn new() -> Self {
        Self {
            initialized: UnsafeCell::new(false),
            value: UnsafeCell::new(MaybeUninit::uninit()),
        }
    }

    unsafe fn initialize(&self, value: KernelVfs) -> Result<(), VfsInitError> {
        if unsafe { *self.initialized.get() } {
            return Err(VfsInitError::AlreadyInitialized);
        }
        unsafe { (*self.value.get()).write(value) };
        unsafe { *self.initialized.get() = true };
        Ok(())
    }

    unsafe fn with_mut<R>(&self, f: impl FnOnce(&mut KernelVfs) -> R) -> Option<R> {
        if !unsafe { *self.initialized.get() } {
            return None;
        }
        Some(f(unsafe { (*self.value.get()).assume_init_mut() }))
    }
}

static VFS: KernelVfsCell = KernelVfsCell::new();

pub unsafe fn initialize() -> Result<(), VfsInitError> {
    let vfs = unsafe { KernelVfs::discover()? };
    unsafe { VFS.initialize(vfs) }
}

pub unsafe fn with_vfs<R>(f: impl FnOnce(&mut KernelVfs) -> R) -> Option<R> {
    unsafe { VFS.with_mut(f) }
}

pub unsafe fn with_volume<R>(id: VolumeId, f: impl FnOnce(&mut KfsVolume) -> R) -> Option<R> {
    unsafe { with_vfs(|vfs| vfs.volume_mut(id).map(f)).flatten() }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sdk_mount_routing_uses_component_boundaries() {
        assert_eq!(route(b"/sdk", true), Route::Sdk(&[]));
        assert_eq!(route(b"/sdk/bin/tcc.kx", true), Route::Sdk(b"/bin/tcc.kx"));
        assert_eq!(route(b"/sdk2/file", true), Route::Root(b"/sdk2/file"));
        assert_eq!(route(b"/sdk-old", true), Route::Root(b"/sdk-old"));
        assert_eq!(route(b"/sdk", false), Route::Root(b"/sdk"));
    }

    #[test]
    fn storage1_profile_requires_the_stable_mmio_window() {
        let valid = k16_abi::computer::profile::HardwareEntry {
            id: k16_abi::computer::hardware_id::STORAGE1,
            mmio_base: k16_abi::computer::storage1::BASE,
            mmio_size: 0x100,
            irq_source: 0,
        };

        assert!(valid_storage1_entry(valid));
        assert!(!valid_storage1_entry(
            k16_abi::computer::profile::HardwareEntry {
                mmio_base: k16_abi::computer::storage0::BASE,
                ..valid
            }
        ));
        assert!(!valid_storage1_entry(
            k16_abi::computer::profile::HardwareEntry {
                mmio_size: 0x80,
                ..valid
            }
        ));
    }
}
