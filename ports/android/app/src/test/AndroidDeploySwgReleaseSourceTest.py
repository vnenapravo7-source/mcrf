"""Queued source guards for the common gate. Never performs network or SSH operations."""
from pathlib import Path
import unittest


SOURCE = Path(__file__).resolve().parent.parent / "main" / "kotlin" / "org" / "mcrf" / "client"


class SwgReleaseSourceGuards(unittest.TestCase):
    def test_resolution_and_digest_gate_precede_vk_and_ssh_on_backend_worker(self):
        backend = (SOURCE / "AndroidDeployBackend.kt").read_text(encoding="utf-8")
        install = backend[backend.index("private fun install("):backend.index("private fun download(")]
        self.assertIn('if(saved==null&&recoveryRecipe.id=="swg")', install)
        self.assertLess(install.index("SwgPanelReleaseResolver("), install.index("vk.resolveCountFor("))
        self.assertLess(install.index("download(recipe,resolved?.bootstrap)"), install.index("vk.resolveCountFor("))
        self.assertLess(install.index("download(recipe,resolved?.bootstrap)"), install.index("val session=jsch.getSession("))
        self.assertLess(backend.index("worker.submit"), backend.index("try { install("))
        self.assertIn("require(hash==recipe.sha256)", backend)
        self.assertIn("if(resolvedBytes!=null)return verifiedInstaller(recipe,resolvedBytes)", backend)
        self.assertIn('if(saved==null)listOfNotNull(installer?', install)
        self.assertIn('else listOf("run.sh"', install)

    def test_resolution_has_no_stale_fallback_and_preserves_saved_source(self):
        backend = (SOURCE / "AndroidDeployBackend.kt").read_text(encoding="utf-8")
        resolver = (SOURCE / "SwgPanelReleaseResolver.kt").read_text(encoding="utf-8")
        self.assertNotIn("releases/latest", resolver)
        resolution = backend[backend.index("val resolved="):backend.index("val recipe=resolved?")]
        self.assertNotIn("runCatching", resolution)
        self.assertNotIn("catch", resolution)
        self.assertIn('else if(saved?.has("core_commit")==true)result.put("core_commit",saved.get("core_commit"))', backend)
        self.assertIn('if(resolved!=null)result.put("core_commit",recipe.commit)', backend)

    def test_master_env_remains_compatible_and_uses_the_resolved_recipe(self):
        scripts = (SOURCE / "AndroidDeployScripts.kt").read_text(encoding="utf-8")
        self.assertIn('env("SWG_REF",recipe.commit)', scripts)
        self.assertIn('env("SWG_REPO","https://github.com/${recipe.repository}")', scripts)
        self.assertIn("bash installer.sh master", scripts)
        for key in ("BASIC_USER", "BASIC_PASS", "PANEL_DOMAIN", "PORT", "TLS_MODE", "SERVE_MODE", "MANAGE_IFACES"):
            self.assertIn('env("' + key + '",', scripts)


if __name__ == "__main__":
    unittest.main()
