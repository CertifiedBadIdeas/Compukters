pub const CREATE_IMAGE: u16 = 0x0001;
pub const CREATE_MASK: u16 = 0x0002;
pub const CREATE_INSTANCES: u16 = 0x0003;
pub const PATCH_IMAGE: u16 = 0x0010;
pub const PATCH_MASK: u16 = 0x0011;
pub const PATCH_INSTANCES: u16 = 0x0012;
pub const DROP_RESOURCE: u16 = 0x0020;
pub const REPLACE_DRAW_LIST: u16 = 0x0030;

pub fn packet(base: u64, operations: &[Vec<u8>]) -> Vec<u8> {
    let mut bytes = Vec::new();
    u32_value(&mut bytes, 0x5550_474b);
    u16_value(&mut bytes, 1);
    u16_value(&mut bytes, 0);
    u32_value(&mut bytes, 0);
    u32_value(&mut bytes, operations.len() as u32);
    u64_value(&mut bytes, base);
    for operation in operations {
        bytes.extend_from_slice(operation);
        bytes.resize(bytes.len().next_multiple_of(4), 0);
    }
    let len = bytes.len() as u32;
    bytes[8..12].copy_from_slice(&len.to_le_bytes());
    bytes
}

pub fn operation(opcode: u16, body: &[u8]) -> Vec<u8> {
    let mut bytes = Vec::new();
    u16_value(&mut bytes, opcode);
    u16_value(&mut bytes, 0);
    u32_value(&mut bytes, (8 + body.len()) as u32);
    bytes.extend_from_slice(body);
    bytes
}

pub fn create_image(id: u32, width: u16, height: u16, pixels: &[u16]) -> Vec<u8> {
    let mut body = Vec::new();
    u32_value(&mut body, id);
    u16_value(&mut body, width);
    u16_value(&mut body, height);
    for pixel in pixels {
        u16_value(&mut body, *pixel);
    }
    operation(CREATE_IMAGE, &body)
}

pub fn create_mask(id: u32, width: u16, height: u16, rows: &[u8]) -> Vec<u8> {
    let mut body = Vec::new();
    u32_value(&mut body, id);
    u16_value(&mut body, width);
    u16_value(&mut body, height);
    body.extend_from_slice(rows);
    operation(CREATE_MASK, &body)
}

pub fn instance(source_x: u16) -> Vec<u8> {
    let mut bytes = Vec::new();
    for value in [source_x, 0, 8, 8] {
        u16_value(&mut bytes, value);
    }
    i16_value(&mut bytes, 0);
    i16_value(&mut bytes, 0);
    for value in [8, 8, 0xffff, 0, 1, 0] {
        u16_value(&mut bytes, value);
    }
    bytes
}

pub fn create_instances(id: u32, records: &[Vec<u8>]) -> Vec<u8> {
    let mut body = Vec::new();
    u32_value(&mut body, id);
    u16_value(&mut body, records.len() as u16);
    u16_value(&mut body, 0);
    for record in records {
        body.extend_from_slice(record);
    }
    operation(CREATE_INSTANCES, &body)
}

pub fn patch_image(id: u32, x: u16, y: u16, width: u16, height: u16, pixels: &[u16]) -> Vec<u8> {
    let mut payload = Vec::new();
    for pixel in pixels {
        u16_value(&mut payload, *pixel);
    }
    patch_rect(PATCH_IMAGE, id, x, y, width, height, &payload)
}

pub fn patch_mask(id: u32, x: u16, y: u16, width: u16, height: u16, rows: &[u8]) -> Vec<u8> {
    patch_rect(PATCH_MASK, id, x, y, width, height, rows)
}

pub fn patch_instances(id: u32, start: u16, records: &[Vec<u8>]) -> Vec<u8> {
    let mut body = Vec::new();
    u32_value(&mut body, id);
    u16_value(&mut body, start);
    u16_value(&mut body, records.len() as u16);
    for record in records {
        body.extend_from_slice(record);
    }
    operation(PATCH_INSTANCES, &body)
}

pub fn drop_resource(id: u32) -> Vec<u8> {
    operation(DROP_RESOURCE, &id.to_le_bytes())
}

pub fn replace_draw_list(background: u16, commands: &[Vec<u8>]) -> Vec<u8> {
    let mut body = Vec::new();
    u16_value(&mut body, background);
    u16_value(&mut body, 0);
    u32_value(&mut body, commands.len() as u32);
    for command in commands {
        body.extend_from_slice(command);
        body.resize(body.len().next_multiple_of(4), 0);
    }
    operation(REPLACE_DRAW_LIST, &body)
}

pub fn draw_mask_instances(mask_id: u32, instances_id: u32, count: u16) -> Vec<u8> {
    draw_mask_instances_range(mask_id, instances_id, 0, count)
}

pub fn draw_mask_instances_range(
    mask_id: u32,
    instances_id: u32,
    first: u16,
    count: u16,
) -> Vec<u8> {
    let mut body = Vec::new();
    u32_value(&mut body, mask_id);
    u32_value(&mut body, instances_id);
    u16_value(&mut body, first);
    u16_value(&mut body, count);
    i16_value(&mut body, 0);
    i16_value(&mut body, 0);
    let mut command = Vec::new();
    u16_value(&mut command, 0x0022);
    u16_value(&mut command, 0);
    u32_value(&mut command, 24);
    command.extend_from_slice(&body);
    command
}

pub fn draw_image(resource_id: u32, width: u16, height: u16) -> Vec<u8> {
    let mut body = Vec::new();
    u32_value(&mut body, resource_id);
    for value in [0, 0, width, height] {
        u16_value(&mut body, value);
    }
    i16_value(&mut body, 0);
    i16_value(&mut body, 0);
    u16_value(&mut body, width);
    u16_value(&mut body, height);
    let mut command = Vec::new();
    u16_value(&mut command, 0x0020);
    u16_value(&mut command, 0);
    u32_value(&mut command, 28);
    command.extend_from_slice(&body);
    command
}

pub fn push_clip(width: u16, height: u16) -> Vec<u8> {
    let mut body = Vec::new();
    i16_value(&mut body, 0);
    i16_value(&mut body, 0);
    u16_value(&mut body, width);
    u16_value(&mut body, height);
    let mut command = Vec::new();
    u16_value(&mut command, 0x0001);
    u16_value(&mut command, 0);
    u32_value(&mut command, 16);
    command.extend_from_slice(&body);
    command
}

fn patch_rect(
    opcode: u16,
    id: u32,
    x: u16,
    y: u16,
    width: u16,
    height: u16,
    payload: &[u8],
) -> Vec<u8> {
    let mut body = Vec::new();
    u32_value(&mut body, id);
    for value in [x, y, width, height] {
        u16_value(&mut body, value);
    }
    body.extend_from_slice(payload);
    operation(opcode, &body)
}

fn u16_value(bytes: &mut Vec<u8>, value: u16) {
    bytes.extend_from_slice(&value.to_le_bytes());
}

fn i16_value(bytes: &mut Vec<u8>, value: i16) {
    bytes.extend_from_slice(&value.to_le_bytes());
}

fn u32_value(bytes: &mut Vec<u8>, value: u32) {
    bytes.extend_from_slice(&value.to_le_bytes());
}

fn u64_value(bytes: &mut Vec<u8>, value: u64) {
    bytes.extend_from_slice(&value.to_le_bytes());
}
