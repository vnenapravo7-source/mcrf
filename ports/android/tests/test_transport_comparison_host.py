"""Scoped wiring contracts; native geometry and VPN behavior still require Android."""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCES = ROOT / 'app/src/main/kotlin/org/mcrf/client'


class TransportComparisonHostTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ui = (SOURCES / 'TransportComparisonUi.kt').read_text(encoding='utf-8')
        cls.provider = (SOURCES / 'TransportComparisonNativeProvider.kt').read_text(encoding='utf-8')
        cls.probe = (SOURCES / 'ProbeService.kt').read_text(encoding='utf-8')
        cls.section, cls.hidden = cls.ui.split('    fun showHidden(', 1)

    def test_section_uses_activity_surface_and_closes_on_back_and_detach(self):
        self.assertIn('findViewById<ViewGroup>(android.R.id.content)', self.section)
        self.assertIn('surface.addView(section', self.section)
        self.assertNotIn('.setView(scroll)', self.section)
        self.assertNotIn('dialog.show()', self.section)
        self.assertIn('KeyEvent.KEYCODE_BACK', self.section)
        self.assertIn('unregisterOnBackInvokedCallback', self.section)
        self.assertIn('token?.close(); section.removeCallbacks(connectionPoll)', self.section)

    def test_progress_and_actions_are_outside_scroll_and_results_are_cards(self):
        self.assertIn('section.addView(progressText', self.section)
        self.assertIn('section.addView(progressBar', self.section)
        self.assertIn('section.addView(actions)', self.section)
        self.assertIn('report.completed}/${report.total}', self.section)
        self.assertIn('results.addView(card()', self.section)

    def test_only_explicit_disconnect_button_stops_own_vpn_without_consent_or_auto_run(self):
        stop = self.section.split('disconnect.setOnClickListener {', 1)[1].split('        fun choose(', 1)[0]
        self.assertEqual(1, self.section.count('McrfVpnService.STOP'))
        self.assertIn('McrfVpnService.STOP', stop)
        self.assertIn('Settings.ACTION_VPN_SETTINGS', stop)
        self.assertNotIn('performClick', stop)
        self.assertNotIn('VpnService.prepare', self.ui)
        self.assertIn('if (blocked)', self.section)
        self.assertIn('TRANSPORT_VPN', self.provider)

    def test_rejected_bs_groups_are_visible_without_relaxing_native_carrier_gate(self):
        self.assertIn('TransportComparisonPlan.diagnosticGroups(', self.section)
        self.assertIn('TransportComparisonPlan.groupTitle(group)', self.section)
        self.assertIn('group.mapNotNull(TransportComparisonPlan::unavailableReason)', self.section)
        self.assertIn('request.getJSONArray("carriers").length() == 0', self.probe)
        self.assertIn('entry.profileDns.toList(), entry.whitelists', self.provider)

    def test_warning_and_probe_admission_both_check_nondefault_vpn_networks(self):
        blocked = self.provider.split('fun connectionBlocked(', 1)[1].split('fun run(', 1)[0]
        admitted = self.probe.split('fun admitted()', 1)[1].split('admitted()', 1)[0]
        self.assertIn('manager.allNetworks.any {', blocked)
        self.assertNotIn('manager.activeNetwork', blocked)
        self.assertIn('manager.allNetworks.none {', admitted)
        self.assertIn('TRANSPORT_VPN', blocked)
        self.assertIn('TRANSPORT_VPN', admitted)

    def test_hidden_dialog_wraps_up_to_six_rows_and_scrolls_after_six(self):
        self.assertIn('minOf(rows.childCount, 6)', self.hidden)
        self.assertIn('MeasureSpec.AT_MOST', self.hidden)
        self.assertIn('if (entries.isEmpty()) View.GONE else View.VISIBLE', self.hidden)
        self.assertIn('TransportComparisonPlan.hiddenRowsScroll(entries.size)', self.hidden)
        self.assertNotIn('height = (resources.displayMetrics.heightPixels', self.hidden)


if __name__ == '__main__':
    unittest.main()
