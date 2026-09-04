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

    def __init__(self, response=None):
        self.requests = []
        self.response = (
            {"id": 1, "sourceUri": "https://t.me/news"}
            if response is None else response
        )

    def request(self, method, path, body=None):
        self.requests.append((method, path, body))
        return self.response


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
            "destination", "add", "news", "--no-profile-sync"
        ])
        client = RecordingClient()

        with contextlib.redirect_stdout(io.StringIO()):
            MODULE.run(arguments, client)

        self.assertEqual(client.requests, [(
            "POST",
            "/api/destinations",
            {
                "nodeName": "news",
                "profileSync": False,
            },
        )])

    def test_destination_list_does_not_print_credentials(self):
        arguments = MODULE.parser().parse_args(["destination", "list"])
        client = RecordingClient()
        client.request = lambda method, path, body=None: [{
            "id": 1,
            "nodeName": "news",
            "profileSync": True,
        }]

        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            MODULE.run(arguments, client)

        self.assertNotIn("CREDENTIAL", output.getvalue())

    def test_mirror_list_requests_and_prints_mirrors(self):
        try:
            arguments = MODULE.parser().parse_args(["mirror", "list"])
        except SystemExit:
            self.fail("mirror list should be accepted")
        client = RecordingClient([{
            "id": 7,
            "channelId": 2,
            "destinationId": 3,
            "feeds": ["timeline", "news"],
        }])

        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            MODULE.run(arguments, client)

        self.assertEqual(client.requests, [("GET", "/api/mirrors", None)])
        self.assertEqual(output.getvalue(), (
            "ID  CHANNEL  DESTINATION  FEEDS        \n"
            "--  -------  -----------  -------------\n"
            "7   2        3            timeline,news\n"
        ))


if __name__ == "__main__":
    unittest.main()
