import contextlib
import importlib.machinery
import importlib.util
import io
from pathlib import Path
import unittest


SCRIPT_PATH = Path(__file__).with_name("moera-import-admin")
LOADER = importlib.machinery.SourceFileLoader("moera_import_admin", str(SCRIPT_PATH))
SPEC = importlib.util.spec_from_loader(LOADER.name, LOADER)
MODULE = importlib.util.module_from_spec(SPEC)
LOADER.exec_module(MODULE)


class RecordingClient:

    def __init__(self):
        self.requests = []

    def request(self, method, path, body=None):
        self.requests.append((method, path, body))
        return {"id": 1, "sourceUri": "https://t.me/news"}


class MoeraImportAdminTest(unittest.TestCase):

    def test_channel_add_defaults_to_recent_history(self):
        arguments = MODULE.parser().parse_args(["channel", "add", "https://t.me/news"])

        self.assertFalse(arguments.full_history)

    def test_channel_add_parses_full_history_flag(self):
        arguments = MODULE.parser().parse_args([
            "channel", "add", "https://t.me/news", "--full-history"
        ])

        self.assertTrue(arguments.full_history)

    def test_channel_add_sends_full_history_in_request_body(self):
        arguments = MODULE.parser().parse_args([
            "channel", "add", "https://t.me/news", "--full-history"
        ])
        client = RecordingClient()

        with contextlib.redirect_stdout(io.StringIO()):
            MODULE.run(arguments, client)

        self.assertEqual(client.requests, [(
            "POST",
            "/api/channels",
            {
                "sourceType": "TELEGRAM",
                "sourceUri": "https://t.me/news",
                "fullHistory": True,
            },
        )])

    def test_destination_add_can_disable_profile_sync(self):
        arguments = MODULE.parser().parse_args([
            "destination", "add", "news", "env:TOKEN", "--no-profile-sync"
        ])
        client = RecordingClient()

        with contextlib.redirect_stdout(io.StringIO()):
            MODULE.run(arguments, client)

        self.assertEqual(client.requests, [(
            "POST",
            "/api/destinations",
            {
                "nodeName": "news",
                "credentialRef": "env:TOKEN",
                "profileSync": False,
            },
        )])


if __name__ == "__main__":
    unittest.main()
