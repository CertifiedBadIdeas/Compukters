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

#[cfg(test)]
mod tests {
    use crate::rv32im::Rv32ArchitecturalState;

    #[test]
    fn architectural_state_preserves_x0_and_register_values() {
        let mut state = Rv32ArchitecturalState::new(0x1000);
        state.set_register(0, 0xfeed_beef);
        state.set_register(7, 42);

        assert_eq!(state.pc(), 0x1000);
        assert_eq!(state.register(0), 0);
        assert_eq!(state.register(7), 42);
    }
}
