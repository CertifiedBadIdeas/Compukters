pub const MASK_INSTANCE_OPAQUE_BACKGROUND: u16 = 1;
const MASK_INSTANCE_WIRE_BYTES: usize = 24;

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum ResourceValidationError {
    #[error("resource extent or count must be non-zero")]
    ZeroExtent,
    #[error("resource payload length mismatch: expected {expected}, got {actual}")]
    PayloadLength { expected: usize, actual: usize },
    #[error(
        "mask row {row} has non-zero unused bits in {byte:#04x}; allowed mask is {allowed_mask:#04x}"
    )]
    UnusedMaskBits {
        row: usize,
        byte: u8,
        allowed_mask: u8,
    },
    #[error("unknown mask-instance flags {0:#06x}")]
    UnknownInstanceFlags(u16),
    #[error("reserved mask-instance field must be zero, got {0:#06x}")]
    NonZeroReserved(u16),
    #[error("cutout mask instance must have zero background, got {0:#06x}")]
    CutoutBackground(u16),
    #[error("resource range is out of bounds")]
    OutOfBounds,
    #[error("resource size arithmetic overflow")]
    SizeOverflow,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ImageRgb565 {
    width: u16,
    height: u16,
    pixels: Vec<u16>,
}

impl ImageRgb565 {
    pub fn new(width: u16, height: u16, pixels: Vec<u16>) -> Result<Self, ResourceValidationError> {
        let expected = checked_area(width, height)?;
        require_payload_len(expected, pixels.len())?;
        Ok(Self {
            width,
            height,
            pixels,
        })
    }

    pub fn width(&self) -> u16 {
        self.width
    }

    pub fn height(&self) -> u16 {
        self.height
    }

    pub fn pixels(&self) -> &[u16] {
        &self.pixels
    }

    pub fn patch_rect(
        &mut self,
        x: u16,
        y: u16,
        width: u16,
        height: u16,
        pixels: &[u16],
    ) -> Result<(), ResourceValidationError> {
        validate_rect(self.width, self.height, x, y, width, height)?;
        let expected = checked_area(width, height)?;
        require_payload_len(expected, pixels.len())?;

        let destination_width = usize::from(self.width);
        let patch_width = usize::from(width);
        for row in 0..usize::from(height) {
            let destination_start = (usize::from(y) + row) * destination_width + usize::from(x);
            let source_start = row * patch_width;
            self.pixels[destination_start..destination_start + patch_width]
                .copy_from_slice(&pixels[source_start..source_start + patch_width]);
        }
        Ok(())
    }

    pub fn payload_bytes(&self) -> usize {
        self.pixels.len() * size_of::<u16>()
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Mask1Bpp {
    width: u16,
    height: u16,
    rows: Vec<u8>,
}

impl Mask1Bpp {
    pub fn new(width: u16, height: u16, rows: Vec<u8>) -> Result<Self, ResourceValidationError> {
        require_non_zero(width, height)?;
        let row_bytes = mask_row_bytes(width)?;
        let expected = row_bytes
            .checked_mul(usize::from(height))
            .ok_or(ResourceValidationError::SizeOverflow)?;
        require_payload_len(expected, rows.len())?;
        validate_mask_padding(width, height, &rows)?;
        Ok(Self {
            width,
            height,
            rows,
        })
    }

    pub fn width(&self) -> u16 {
        self.width
    }

    pub fn height(&self) -> u16 {
        self.height
    }

    pub fn row_bytes(&self) -> usize {
        mask_row_bytes(self.width).expect("validated non-zero mask width")
    }

    pub fn rows(&self) -> &[u8] {
        &self.rows
    }

    pub fn patch_rect(
        &mut self,
        x: u16,
        y: u16,
        width: u16,
        height: u16,
        rows: &[u8],
    ) -> Result<(), ResourceValidationError> {
        validate_rect(self.width, self.height, x, y, width, height)?;
        let patch_row_bytes = mask_row_bytes(width)?;
        let expected = patch_row_bytes
            .checked_mul(usize::from(height))
            .ok_or(ResourceValidationError::SizeOverflow)?;
        require_payload_len(expected, rows.len())?;
        validate_mask_padding(width, height, rows)?;

        let destination_row_bytes = self.row_bytes();
        for row in 0..usize::from(height) {
            for column in 0..usize::from(width) {
                let source_byte = rows[row * patch_row_bytes + column / 8];
                let source_set = source_byte & (0x80 >> (column % 8)) != 0;
                let destination_column = usize::from(x) + column;
                let destination_index =
                    (usize::from(y) + row) * destination_row_bytes + destination_column / 8;
                let destination_mask = 0x80 >> (destination_column % 8);
                if source_set {
                    self.rows[destination_index] |= destination_mask;
                } else {
                    self.rows[destination_index] &= !destination_mask;
                }
            }
        }
        Ok(())
    }

    pub fn payload_bytes(&self) -> usize {
        self.rows.len()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MaskInstanceRecord {
    pub source_x: u16,
    pub source_y: u16,
    pub source_width: u16,
    pub source_height: u16,
    pub destination_x: i16,
    pub destination_y: i16,
    pub destination_width: u16,
    pub destination_height: u16,
    pub foreground_rgb565: u16,
    pub background_rgb565: u16,
    pub flags: u16,
    pub reserved: u16,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MaskInstance {
    record: MaskInstanceRecord,
}

impl MaskInstance {
    pub fn new(record: MaskInstanceRecord) -> Result<Self, ResourceValidationError> {
        require_non_zero(record.source_width, record.source_height)?;
        require_non_zero(record.destination_width, record.destination_height)?;
        if record.flags & !MASK_INSTANCE_OPAQUE_BACKGROUND != 0 {
            return Err(ResourceValidationError::UnknownInstanceFlags(record.flags));
        }
        if record.reserved != 0 {
            return Err(ResourceValidationError::NonZeroReserved(record.reserved));
        }
        if record.flags & MASK_INSTANCE_OPAQUE_BACKGROUND == 0 && record.background_rgb565 != 0 {
            return Err(ResourceValidationError::CutoutBackground(
                record.background_rgb565,
            ));
        }
        Ok(Self { record })
    }

    pub fn record(self) -> MaskInstanceRecord {
        self.record
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MaskInstanceBuffer {
    capacity: u16,
    instances: Vec<MaskInstance>,
}

impl MaskInstanceBuffer {
    pub fn new(
        capacity: u16,
        instances: Vec<MaskInstance>,
    ) -> Result<Self, ResourceValidationError> {
        if capacity == 0 {
            return Err(ResourceValidationError::ZeroExtent);
        }
        require_payload_len(usize::from(capacity), instances.len())?;
        Ok(Self {
            capacity,
            instances,
        })
    }

    pub fn capacity(&self) -> u16 {
        self.capacity
    }

    pub fn instances(&self) -> &[MaskInstance] {
        &self.instances
    }

    pub fn patch(
        &mut self,
        start_index: u16,
        instances: &[MaskInstance],
    ) -> Result<(), ResourceValidationError> {
        if instances.is_empty() {
            return Err(ResourceValidationError::ZeroExtent);
        }
        let start = usize::from(start_index);
        let end = start
            .checked_add(instances.len())
            .ok_or(ResourceValidationError::SizeOverflow)?;
        if end > self.instances.len() {
            return Err(ResourceValidationError::OutOfBounds);
        }
        self.instances[start..end].copy_from_slice(instances);
        Ok(())
    }

    pub fn payload_bytes(&self) -> usize {
        self.instances.len() * MASK_INSTANCE_WIRE_BYTES
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Resource {
    ImageRgb565(ImageRgb565),
    Mask1Bpp(Mask1Bpp),
    MaskInstanceBuffer(MaskInstanceBuffer),
}

impl Resource {
    pub fn payload_bytes(&self) -> usize {
        match self {
            Self::ImageRgb565(image) => image.payload_bytes(),
            Self::Mask1Bpp(mask) => mask.payload_bytes(),
            Self::MaskInstanceBuffer(instances) => instances.payload_bytes(),
        }
    }
}

fn checked_area(width: u16, height: u16) -> Result<usize, ResourceValidationError> {
    require_non_zero(width, height)?;
    usize::from(width)
        .checked_mul(usize::from(height))
        .ok_or(ResourceValidationError::SizeOverflow)
}

fn mask_row_bytes(width: u16) -> Result<usize, ResourceValidationError> {
    if width == 0 {
        return Err(ResourceValidationError::ZeroExtent);
    }
    usize::from(width)
        .checked_add(7)
        .map(|bits| bits / 8)
        .ok_or(ResourceValidationError::SizeOverflow)
}

fn require_non_zero(width: u16, height: u16) -> Result<(), ResourceValidationError> {
    if width == 0 || height == 0 {
        Err(ResourceValidationError::ZeroExtent)
    } else {
        Ok(())
    }
}

fn require_payload_len(expected: usize, actual: usize) -> Result<(), ResourceValidationError> {
    if expected == actual {
        Ok(())
    } else {
        Err(ResourceValidationError::PayloadLength { expected, actual })
    }
}

fn validate_rect(
    resource_width: u16,
    resource_height: u16,
    x: u16,
    y: u16,
    width: u16,
    height: u16,
) -> Result<(), ResourceValidationError> {
    require_non_zero(width, height)?;
    let right = u32::from(x) + u32::from(width);
    let bottom = u32::from(y) + u32::from(height);
    if right > u32::from(resource_width) || bottom > u32::from(resource_height) {
        Err(ResourceValidationError::OutOfBounds)
    } else {
        Ok(())
    }
}

fn validate_mask_padding(
    width: u16,
    height: u16,
    rows: &[u8],
) -> Result<(), ResourceValidationError> {
    let used_bits = width % 8;
    if used_bits == 0 {
        return Ok(());
    }
    let allowed_mask = u8::MAX << (8 - used_bits);
    let row_bytes = mask_row_bytes(width)?;
    for row in 0..usize::from(height) {
        let byte = rows[(row + 1) * row_bytes - 1];
        if byte & !allowed_mask != 0 {
            return Err(ResourceValidationError::UnusedMaskBits {
                row,
                byte,
                allowed_mask,
            });
        }
    }
    Ok(())
}
