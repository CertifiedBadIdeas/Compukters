pub fn k16_udiv64(lhs: u64, rhs: u64) -> u64 {
    k16_udivmod64(lhs, rhs).0
}

pub fn k16_umod64(lhs: u64, rhs: u64) -> u64 {
    k16_udivmod64(lhs, rhs).1
}

pub fn k16_div64(lhs: i64, rhs: i64) -> i64 {
    let quotient = k16_udiv64(k16_i64_abs_bits(lhs), k16_i64_abs_bits(rhs));
    if (lhs < 0) == (rhs < 0) {
        quotient as i64
    } else {
        k16_negate_u64_bits(quotient)
    }
}

pub fn k16_mod64(lhs: i64, rhs: i64) -> i64 {
    let remainder = k16_umod64(k16_i64_abs_bits(lhs), k16_i64_abs_bits(rhs));
    if lhs < 0 {
        k16_negate_u64_bits(remainder)
    } else {
        remainder as i64
    }
}

fn k16_udivmod64(lhs: u64, rhs: u64) -> (u64, u64) {
    if rhs == 0 {
        return (0, lhs);
    }

    let mut quotient = 0u64;
    let mut remainder = 0u64;
    let mut bit_index = 64usize;
    while bit_index > 0 {
        bit_index -= 1;
        remainder = (remainder << 1) | ((lhs >> bit_index) & 1);
        if remainder >= rhs {
            remainder -= rhs;
            quotient |= 1u64 << bit_index;
        }
    }
    (quotient, remainder)
}

fn k16_i64_abs_bits(value: i64) -> u64 {
    let bits = value as u64;
    if value < 0 {
        0u64.wrapping_sub(bits)
    } else {
        bits
    }
}

fn k16_negate_u64_bits(value: u64) -> i64 {
    0u64.wrapping_sub(value) as i64
}
