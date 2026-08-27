use std::sync::{Arc, Mutex, TryLockError};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum HandleError {
    Invalid,
    Stale,
    Busy,
    Exhausted,
    Poisoned,
}

pub(crate) struct HandleTable<T> {
    slots: Mutex<Vec<Slot<T>>>,
}

struct Slot<T> {
    generation: u32,
    value: Option<Arc<Mutex<Option<T>>>>,
}

impl<T> Default for HandleTable<T> {
    fn default() -> Self {
        Self {
            slots: Mutex::new(Vec::new()),
        }
    }
}

impl<T> HandleTable<T> {
    pub(crate) fn insert(&self, value: T) -> Result<u64, HandleError> {
        let mut slots = self.slots.lock().map_err(|_| HandleError::Poisoned)?;
        if let Some((index, slot)) = slots
            .iter_mut()
            .enumerate()
            .find(|(_, slot)| slot.value.is_none())
        {
            slot.value = Some(Arc::new(Mutex::new(Some(value))));
            return encode_handle(index, slot.generation);
        }
        let index = slots.len();
        let generation = 1;
        slots.push(Slot {
            generation,
            value: Some(Arc::new(Mutex::new(Some(value)))),
        });
        encode_handle(index, generation)
    }

    pub(crate) fn with<R>(
        &self,
        handle: u64,
        action: impl FnOnce(&mut T) -> R,
    ) -> Result<R, HandleError> {
        let cell = self.resolve(handle)?;
        let mut value = match cell.try_lock() {
            Ok(value) => value,
            Err(TryLockError::WouldBlock) => return Err(HandleError::Busy),
            Err(TryLockError::Poisoned(_)) => return Err(HandleError::Poisoned),
        };
        value.as_mut().map(action).ok_or(HandleError::Stale)
    }

    pub(crate) fn close(&self, handle: u64) -> Result<(), HandleError> {
        let (index, generation) = decode_handle(handle)?;
        let cell = self.resolve(handle)?;
        let mut value = match cell.try_lock() {
            Ok(value) => value,
            Err(TryLockError::WouldBlock) => return Err(HandleError::Busy),
            Err(TryLockError::Poisoned(_)) => return Err(HandleError::Poisoned),
        };
        if value.is_none() {
            return Err(HandleError::Stale);
        }
        let mut slots = self.slots.lock().map_err(|_| HandleError::Poisoned)?;
        let slot = slots.get_mut(index).ok_or(HandleError::Invalid)?;
        if slot.generation != generation
            || slot
                .value
                .as_ref()
                .is_none_or(|stored| !Arc::ptr_eq(stored, &cell))
        {
            return Err(HandleError::Stale);
        }
        *value = None;
        slot.value = None;
        slot.generation = next_generation(slot.generation);
        Ok(())
    }

    pub(crate) fn consume_if<R, E>(
        &self,
        handle: u64,
        action: impl FnOnce(T) -> Result<R, (E, T)>,
    ) -> Result<Result<R, E>, HandleError> {
        let (index, generation) = decode_handle(handle)?;
        let cell = self.resolve(handle)?;
        let mut value = match cell.try_lock() {
            Ok(value) => value,
            Err(TryLockError::WouldBlock) => return Err(HandleError::Busy),
            Err(TryLockError::Poisoned(_)) => return Err(HandleError::Poisoned),
        };
        let owned = value.take().ok_or(HandleError::Stale)?;
        match action(owned) {
            Err((error, restored)) => {
                *value = Some(restored);
                Ok(Err(error))
            }
            Ok(result) => {
                let mut slots = self.slots.lock().map_err(|_| HandleError::Poisoned)?;
                let slot = slots.get_mut(index).ok_or(HandleError::Invalid)?;
                if slot.generation != generation
                    || slot
                        .value
                        .as_ref()
                        .is_none_or(|stored| !Arc::ptr_eq(stored, &cell))
                {
                    return Err(HandleError::Stale);
                }
                slot.value = None;
                slot.generation = next_generation(slot.generation);
                Ok(Ok(result))
            }
        }
    }

    fn resolve(&self, handle: u64) -> Result<Arc<Mutex<Option<T>>>, HandleError> {
        let (index, generation) = decode_handle(handle)?;
        let slots = self.slots.lock().map_err(|_| HandleError::Poisoned)?;
        let slot = slots.get(index).ok_or(HandleError::Invalid)?;
        if slot.generation != generation {
            return Err(HandleError::Stale);
        }
        slot.value.clone().ok_or(HandleError::Stale)
    }
}

fn encode_handle(index: usize, generation: u32) -> Result<u64, HandleError> {
    let slot = u32::try_from(index)
        .ok()
        .and_then(|index| index.checked_add(1))
        .ok_or(HandleError::Exhausted)?;
    Ok((u64::from(generation) << 32) | u64::from(slot))
}

fn decode_handle(handle: u64) -> Result<(usize, u32), HandleError> {
    let slot = handle as u32;
    let generation = (handle >> 32) as u32;
    if slot == 0 || generation == 0 {
        return Err(HandleError::Invalid);
    }
    Ok(((slot - 1) as usize, generation))
}

fn next_generation(generation: u32) -> u32 {
    let next = generation.wrapping_add(1);
    if next == 0 {
        1
    } else {
        next
    }
}

#[cfg(test)]
mod tests {
    use std::{
        sync::{mpsc, Arc},
        thread,
    };

    use super::{HandleError, HandleTable};

    #[test]
    fn close_invalidates_the_handle_and_double_close_is_stale() {
        let table = HandleTable::default();
        let first = table.insert(7_u32).unwrap();

        assert_eq!(7, table.with(first, |value| *value).unwrap());
        table.close(first).unwrap();
        assert_eq!(Err(HandleError::Stale), table.with(first, |_| ()));
        assert_eq!(Err(HandleError::Stale), table.close(first));

        let replacement = table.insert(9_u32).unwrap();
        assert_ne!(first, replacement);
        assert_eq!(9, table.with(replacement, |value| *value).unwrap());
    }

    #[test]
    fn concurrent_use_is_rejected_instead_of_blocking() {
        let table = Arc::new(HandleTable::default());
        let handle = table.insert(1_u32).unwrap();
        let (entered_tx, entered_rx) = mpsc::channel();
        let (release_tx, release_rx) = mpsc::channel();
        let worker_table = Arc::clone(&table);
        let worker = thread::spawn(move || {
            worker_table
                .with(handle, |value| {
                    entered_tx.send(()).unwrap();
                    release_rx.recv().unwrap();
                    *value += 1;
                })
                .unwrap();
        });

        entered_rx.recv().unwrap();
        assert_eq!(Err(HandleError::Busy), table.with(handle, |_| ()));
        assert_eq!(Err(HandleError::Busy), table.close(handle));
        release_tx.send(()).unwrap();
        worker.join().unwrap();
        assert_eq!(2, table.with(handle, |value| *value).unwrap());
    }

    #[test]
    fn conditional_consumption_restores_on_failure_and_invalidates_on_success() {
        let table = HandleTable::default();
        let handle = table.insert(7_u32).unwrap();

        assert_eq!(
            Ok(Err("retry")),
            table.consume_if(handle, |value| Err::<(), _>(("retry", value + 1)))
        );
        assert_eq!(8, table.with(handle, |value| *value).unwrap());
        assert_eq!(
            Ok(Ok(9)),
            table.consume_if(handle, |value| Ok::<_, (&str, u32)>(value + 1))
        );
        assert_eq!(Err(HandleError::Stale), table.with(handle, |_| ()));
    }
}
