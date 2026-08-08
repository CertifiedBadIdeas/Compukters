#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct StorageError {
    code: i32,
}

impl StorageError {
    pub const STORAGE_VERSION: Self = Self { code: 10 };
    pub const INVALID_PARTITION_TABLE: Self = Self { code: 11 };
    pub const PARTITION_NOT_FOUND: Self = Self { code: 12 };
    pub const INVALID_FILESYSTEM: Self = Self { code: 13 };
    pub const PATH_NOT_FOUND: Self = Self { code: 14 };
    pub const STORAGE_TRANSFER: Self = Self { code: 16 };
    pub const STORAGE_BLOCK_SIZE: Self = Self { code: 17 };
    pub const STORAGE_MEDIA: Self = Self { code: 18 };
    pub const OUTPUT_BUFFER_TOO_SMALL: Self = Self { code: 19 };
    pub const OUTPUT_TRANSFER: Self = Self { code: 20 };
    pub const PATH_NOT_EMPTY: Self = Self { code: 21 };
    pub const PATH_EXISTS: Self = Self { code: 22 };
    pub const PATH_NOT_REGULAR: Self = Self { code: 23 };
    pub const PATH_BUSY: Self = Self { code: 24 };
    pub const READ_ONLY: Self = Self { code: 25 };

    pub const fn code(self) -> i32 {
        self.code
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn storage_error_code_is_public_for_boot_chain_mapping() {
        assert_eq!(StorageError::STORAGE_VERSION.code(), 10);
        assert_eq!(StorageError::OUTPUT_BUFFER_TOO_SMALL.code(), 19);
        assert_eq!(StorageError::PATH_EXISTS.code(), 22);
        assert_eq!(StorageError::PATH_NOT_REGULAR.code(), 23);
        assert_eq!(StorageError::PATH_BUSY.code(), 24);
        assert_eq!(StorageError::READ_ONLY.code(), 25);
    }
}
