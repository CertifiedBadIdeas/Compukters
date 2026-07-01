use crate::{kfs, volume};

pub fn read_file_from_partition(
    volume_image: &[u8],
    partition: &str,
    path: &str,
) -> Result<Vec<u8>, String> {
    let partition_image = volume::extract_partition(volume_image, partition)?;
    kfs::read_file(&partition_image, path)
}
