#![no_std]

pub use kraft_std::coreutils::for_each_path_arg;

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

    #[test]
    fn for_each_path_arg_visits_later_paths_after_error() {
        let raw = [b"/etc/missing".as_slice(), b"/etc/motd".as_slice()];
        let mut seen = Vec::new();

        let result = super::for_each_path_arg(
            raw.len(),
            |index| raw.get(index).copied(),
            |path| {
                seen.push(path.to_owned());
                if path == "/etc/missing" {
                    Err(())
                } else {
                    Ok(())
                }
            },
        );

        assert_eq!(result, Err(()));
        assert_eq!(seen, ["/etc/missing", "/etc/motd"]);
    }
}
