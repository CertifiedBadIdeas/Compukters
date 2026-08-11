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

#![allow(
    dead_code,
    reason = "the planner is introduced before the machine-owned JIT dispatcher"
)]

use super::block::JitBlockInput;
use std::collections::VecDeque;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct JitPlannerConfig {
    pub(crate) hotness_threshold: u32,
    pub(crate) candidate_capacity: usize,
    pub(crate) request_capacity: usize,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum JitPlanAction {
    InterpretOnly,
    Cold,
    Queued,
    AlreadyQueued,
    RejectedCapacity,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct JitCandidate {
    input: JitBlockInput,
    hits: u32,
    queued: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct JitPlanner {
    config: JitPlannerConfig,
    candidates: Vec<JitCandidate>,
    requests: VecDeque<JitBlockInput>,
}

impl JitPlanner {
    pub(crate) fn new(config: JitPlannerConfig) -> Result<Self, String> {
        if config.hotness_threshold == 0 {
            return Err("RV32 JIT hotness threshold must be positive".to_string());
        }
        if config.candidate_capacity == 0 {
            return Err("RV32 JIT candidate capacity must be positive".to_string());
        }
        if config.request_capacity == 0 {
            return Err("RV32 JIT request capacity must be positive".to_string());
        }
        Ok(Self {
            candidates: Vec::with_capacity(config.candidate_capacity),
            requests: VecDeque::with_capacity(config.request_capacity),
            config,
        })
    }

    pub(crate) fn observe(&mut self, input: JitBlockInput) -> JitPlanAction {
        if !input.is_supported() {
            return JitPlanAction::InterpretOnly;
        }
        let candidate_index = match self
            .candidates
            .iter()
            .position(|candidate| candidate.input == input)
        {
            Some(index) => index,
            None if self.candidates.len() < self.config.candidate_capacity => {
                self.candidates.push(JitCandidate {
                    input,
                    hits: 0,
                    queued: false,
                });
                self.candidates
                    .len()
                    .checked_sub(1)
                    .expect("pushed RV32 JIT candidate must have an index")
            }
            None => return JitPlanAction::RejectedCapacity,
        };
        let request = {
            let candidate = &mut self.candidates[candidate_index];
            if candidate.queued {
                return JitPlanAction::AlreadyQueued;
            }
            candidate.hits = candidate.hits.saturating_add(1);
            if candidate.hits < self.config.hotness_threshold {
                return JitPlanAction::Cold;
            }
            candidate.input.clone()
        };
        if self.requests.len() == self.config.request_capacity {
            return JitPlanAction::RejectedCapacity;
        }
        self.candidates[candidate_index].queued = true;
        self.requests.push_back(request);
        JitPlanAction::Queued
    }

    pub(crate) fn take_requests(&mut self, max_blocks: usize) -> Vec<JitBlockInput> {
        self.requests
            .drain(..max_blocks.min(self.requests.len()))
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::{JitPlanAction, JitPlanner, JitPlannerConfig};
    use crate::rv32_jit::block::JitBlockInput;
    use crate::rv32im::{
        decode_product_word,
        encoding::{addi, fence_i},
        Rv32ResolvedInstruction,
    };

    fn block_at(pc: u32) -> JitBlockInput {
        let word = addi(1, 1, 1);
        JitBlockInput::from_resolved(
            pc,
            vec![Rv32ResolvedInstruction::Valid {
                word,
                instruction: decode_product_word(word).unwrap(),
            }],
        )
        .unwrap()
    }

    #[test]
    fn threshold_queues_exactly_one_pure_request() {
        let mut planner = JitPlanner::new(JitPlannerConfig {
            hotness_threshold: 2,
            candidate_capacity: 4,
            request_capacity: 4,
        })
        .unwrap();
        let block = block_at(0x1000);

        assert_eq!(planner.observe(block.clone()), JitPlanAction::Cold);
        assert_eq!(planner.observe(block.clone()), JitPlanAction::Queued);
        assert_eq!(planner.observe(block.clone()), JitPlanAction::AlreadyQueued);
        assert_eq!(planner.take_requests(8), vec![block]);
        assert!(planner.take_requests(8).is_empty());
    }

    #[test]
    fn planner_never_queues_fence_i() {
        let mut planner = JitPlanner::new(JitPlannerConfig {
            hotness_threshold: 1,
            candidate_capacity: 4,
            request_capacity: 4,
        })
        .unwrap();
        let word = fence_i();
        let block = JitBlockInput::from_resolved(
            0x1000,
            vec![Rv32ResolvedInstruction::Valid {
                word,
                instruction: decode_product_word(word).unwrap(),
            }],
        )
        .unwrap();

        assert_eq!(planner.observe(block), JitPlanAction::InterpretOnly);
        assert!(planner.take_requests(1).is_empty());
    }
}
