fn main() {
    match focaaa_openmls::run_two_member_snapshot_probe() {
        Ok(result) => println!("{result}"),
        Err(error) => {
            eprintln!("two_member_snapshot_probe_failed={error}");
            std::process::exit(1);
        }
    }
}
