"""Focused producer-contract tests; no compiler, network or generated inputs."""
import importlib.util
import pathlib
import unittest

SCRIPT = pathlib.Path(__file__).with_name("prepare-csqtt-streams.py")
spec = importlib.util.spec_from_file_location("stream_adapter", SCRIPT)
adapter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(adapter)


class StreamAdapterTest(unittest.TestCase):
    main = "mod mcrf_captcha_waiters;\n"
    session = """struct ActiveConnection {
    stats: Arc<Stats>,
    events: Events,
}
impl ActiveConnection {
    fn new(stats: Arc<Stats>, events: Events) -> Self {
        stats.active_connections.fetch_add(1, Ordering::Relaxed);
        Self { stats, events }
    }
}
impl Drop for ActiveConnection {
    fn drop(&mut self) {
        if self.stats.active_connections.fetch_sub(1, Ordering::AcqRel) == 1 {
            self.events.active_zero();
        }
    }
}
"""

    def test_owner_instrumentation_preserves_upstream_lifecycle(self):
        main, session = adapter.transform(self.main, self.session)
        self.assertEqual(main.count("mod mcrf_streams;"), 1)
        self.assertEqual(session.count("Owner::new()"), 1)
        self.assertIn("self.events.active_zero();", session)
        with self.assertRaises(ValueError):
            adapter.transform(main, session)

    def test_changed_lifecycle_fails_closed(self):
        with self.assertRaises(ValueError):
            adapter.transform(self.main, self.session.replace("Ordering::AcqRel", "Ordering::Relaxed"))

    def test_pristine_or_generated_artifact_boundaries_are_rejected(self):
        for root in ("D:/MCRF/Projects/MCRF/work/android-upstream/csqtt-legacy71712b",
                     "D:/MCRF/Builds/Android/release/source",
                     "D:/MCRF/Builds/Android/csqtt-historical-not-a-guid/source"):
            with self.assertRaises(ValueError):
                adapter.adapt(root)


if __name__ == "__main__":
    unittest.main()
