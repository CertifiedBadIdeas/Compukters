#![no_std]

pub const DEFAULT_PATH: &str = "/bin";

pub fn for_each_path_arg_or_default<'a>(
    arg_count: usize,
    mut arg_at: impl FnMut(usize) -> Option<&'a [u8]>,
    mut visit: impl FnMut(&str) -> Result<(), ()>,
) -> Result<(), ()> {
    if arg_count == 0 {
        return visit(DEFAULT_PATH);
    }
    let mut index = 0;
    let mut failed = false;
    while index < arg_count {
        let path = arg_at(index).ok_or(())?;
        let path = core::str::from_utf8(path).map_err(|_| ())?;
        if visit(path).is_err() {
            failed = true;
        }
        index += 1;
    }
    if failed {
        Err(())
    } else {
        Ok(())
    }
}

#[cfg(test)]
extern crate std;

#[cfg(test)]
mod tests {
    use std::borrow::ToOwned;
    use std::vec::Vec;

    #[test]
    fn for_each_path_arg_or_default_visits_all_argv_paths_in_order() {
        let raw = [b"/".as_slice(), b"/bin".as_slice()];
        let mut seen = Vec::new();

        let result = super::for_each_path_arg_or_default(
            raw.len(),
            |index| raw.get(index).copied(),
            |path| {
                seen.push(path.to_owned());
                Ok(())
            },
        );

        assert_eq!(result, Ok(()));
        assert_eq!(seen, ["/", "/bin"]);
    }

    #[test]
    fn for_each_path_arg_or_default_uses_bin_without_argv() {
        let mut seen = Vec::new();

        let result = super::for_each_path_arg_or_default(
            0,
            |_| None,
            |path| {
                seen.push(path.to_owned());
                Ok(())
            },
        );

        assert_eq!(result, Ok(()));
        assert_eq!(seen, ["/bin"]);
    }

    #[test]
    fn for_each_path_arg_or_default_visits_later_paths_after_error() {
        let raw = [b"/missing".as_slice(), b"/bin".as_slice()];
        let mut seen = Vec::new();

        let result = super::for_each_path_arg_or_default(
            raw.len(),
            |index| raw.get(index).copied(),
            |path| {
                seen.push(path.to_owned());
                if path == "/missing" {
                    Err(())
                } else {
                    Ok(())
                }
            },
        );

        assert_eq!(result, Err(()));
        assert_eq!(seen, ["/missing", "/bin"]);
    }
}
