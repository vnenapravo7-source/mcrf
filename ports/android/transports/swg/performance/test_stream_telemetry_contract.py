"""Source wiring regression; no APK, native compiler, device or network."""
import pathlib
import unittest

HERE = pathlib.Path(__file__).parent
ANDROID = HERE.parents[2]


class StreamWiringTests(unittest.TestCase):
    def test_rebased_25_bridge_keeps_stats_ingress(self):
        canonical = (HERE.parent / "mcrf_csqtt25.go").read_text(encoding="utf-8")
        producer = (HERE.parent / "csqtt25/Prepare-P3AbiSource.ps1").read_text(encoding="utf-8")
        self.assertEqual(canonical.count("NormalizeTunnel: csqtt25.NormalizeTunnel,"), 1)
        self.assertIn('$bridge=$bridge.Replace($statsAnchor,', producer)
        self.assertIn("McrfSampleStreamStats: true,", producer)
        self.assertIn("CS25 sampled stream bridge anchor changed", producer)
        regression = (HERE.parent / "mcrf_csqtt25_test.go").read_text(encoding="utf-8")
        self.assertIn("!c.McrfSampleStreamStats", regression)
        for version in ("2.5.1", "2.5.2", "2.5.3"):
            self.assertIn('"swg-panel-' + version + '"', regression)

    def test_producer_copies_both_reporter_and_regression_tests(self):
        producer = (HERE / "Apply-PerformanceSource.ps1").read_text(encoding="utf-8")
        self.assertIn("@('mcrf_streams.go','mcrf_streams_test.go')", producer)
        self.assertIn("mcrfForwardChildStreams(line, c.McrfSampleStreamStats, mcrfNativeStreams)", producer)
        self.assertIn("if($Kind -eq 'csqtt25')", producer)
        self.assertIn("McrfSampleStreamStats: true,", producer)

    def test_android_consumes_native_record_without_inventing_worker_count(self):
        client = ANDROID / "app/src/main/kotlin/org/mcrf/client"
        carrier = (client / "NativeCarrier.kt").read_text(encoding="utf-8")
        streams = (client / "TurnStreams.kt").read_text(encoding="utf-8")
        service = (client / "McrfVpnService.kt").read_text(encoding="utf-8")
        self.assertIn('fmt.Fprintf(r.out, "MCRF_STREAMS %d\\n", count)',
                      (HERE / "mcrf_streams.go").read_text(encoding="utf-8"))
        self.assertIn('const val PREFIX="MCRF_STREAMS"', streams)
        self.assertIn("owner.streamState.offer(bytes)", carrier)
        self.assertIn("internal fun actualStreams():Int=streamState.current()", carrier)
        self.assertIn("carrier?.actualStreams()", service)

    def test_legacy_build_requires_fresh_compatibility_tests(self):
        for script in ("Invoke-HostChecks.ps1", "Build-TurnNative.ps1"):
            source = (HERE / script).read_text(encoding="utf-8")
            for case in ("TestMcrfStreamsRetainedLegacyStatsAndExactUpgrade",
                         "TestMcrfStreamsMalformedExactDoesNotDisableStats",
                         "TestMcrfStreamsSampledClientAndChildReplacement"):
                self.assertIn(case, source)


if __name__ == "__main__":
    unittest.main()
