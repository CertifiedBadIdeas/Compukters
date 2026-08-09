/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

use k16_vm::isa_benchmarks::{native_checksum, IsaBenchmarkWorkload};

fn main() -> Result<(), String> {
    let mut args = std::env::args().skip(1);
    let name = args.next().ok_or("missing workload")?;
    let iterations = args
        .next()
        .ok_or("missing iterations")?
        .parse::<u32>()
        .map_err(|error| format!("invalid iterations: {error}"))?;
    if args.next().is_some() {
        return Err("unexpected checksum arguments".to_string());
    }
    let workload = IsaBenchmarkWorkload::all()
        .iter()
        .copied()
        .find(|workload| workload.name() == name)
        .ok_or_else(|| format!("unknown workload {name}"))?;
    println!("{}", native_checksum(workload, iterations));
    Ok(())
}
