#![no_std]

pub fn for_each_path_arg<'a>(
    arg_count: usize,
    mut arg_at: impl FnMut(usize) -> Option<&'a [u8]>,
    mut visit: impl FnMut(&str) -> Result<(), ()>,
) -> Result<(), ()> {
    if arg_count == 0 {
        return Err(());
    }
    let mut index = 0;
    while index < arg_count {
        let path = arg_at(index).ok_or(())?;
        let path = core::str::from_utf8(path).map_err(|_| ())?;
        visit(path)?;
        index += 1;
    }
    Ok(())
}

#[cfg(test)]
extern crate std;

#[cfg(test)]
mod tests {
    use std::borrow::ToOwned;
    use std::vec::Vec;

    #[test]
    fn for_each_path_arg_visits_all_argv_paths_in_order() {
        let raw = [b"/etc/motd".as_slice(), b"/etc/motd2".as_slice()];
        let mut seen = Vec::new();

        let result = super::for_each_path_arg(
            raw.len(),
            |index| raw.get(index).copied(),
            |path| {
                seen.push(path.to_owned());
                Ok(())
            },
        );

        assert_eq!(result, Ok(()));
        assert_eq!(seen, ["/etc/motd", "/etc/motd2"]);
    }

    #[test]
    fn for_each_path_arg_rejects_empty_argv() {
        let mut seen = Vec::new();

        let result = super::for_each_path_arg(
            0,
            |_| None,
            |path| {
                seen.push(path.to_owned());
                Ok(())
            },
        );

        assert_eq!(result, Err(()));
        assert!(seen.is_empty());
    }
}
