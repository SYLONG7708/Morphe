import copy
import hashlib
import http.client
import io
from pathlib import Path
import socket
import ssl
import tempfile
import traceback
import unittest
from unittest.mock import Mock, patch
import urllib.error

import download_patch_bundle as downloader
from ci_retry import retryable


class PatchDownloadTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.output = Path(self.temp.name) / "patches.mpp"
        self.output.write_bytes(b"last usable bundle")
        self.data = b"official patch bundle"
        self.asset = {
            "id": 123, "name": "patches-2.1.0.mpp", "size": len(self.data),
            "digest": "sha256:" + hashlib.sha256(self.data).hexdigest(),
            "browser_download_url": "https://github.com/MorpheApp/morphe-patches/releases/download/v2.1.0/patches-2.1.0.mpp",
            "url": "https://api.github.com/repos/MorpheApp/morphe-patches/releases/assets/123",
        }
        self.release = {"tag_name": "v2.1.0", "assets": [self.asset]}
        self.opener = Mock()
        factory = patch.object(downloader.urllib.request, "build_opener", return_value=self.opener)
        self.factory = factory.start()
        self.addCleanup(factory.stop)

    def error(self, status):
        return urllib.error.HTTPError(self.asset["browser_download_url"], status, "unavailable", {}, None)

    def assert_retained(self):
        self.assertEqual(b"last usable bundle", self.output.read_bytes())
        self.assertEqual([self.output], list(self.output.parent.iterdir()))

    def test_primary_download_is_verified_and_replaces_only_on_success(self):
        self.opener.open.return_value = io.BytesIO(self.data)
        downloader.download(self.release, self.output)
        self.assertEqual(self.data, self.output.read_bytes())
        self.opener.open.assert_called_once()
        request = self.opener.open.call_args.args[0]
        self.assertEqual(self.asset["browser_download_url"], request.full_url)
        self.assertEqual([self.output], list(self.output.parent.iterdir()))

    def test_gateway_failure_uses_the_same_assets_api_endpoint(self):
        for status in (429, 500, 502, 503, 504):
            with self.subTest(status=status):
                self.opener.reset_mock()
                self.opener.open.side_effect = [self.error(status), io.BytesIO(self.data)]
                downloader.download(self.release, self.output)
                self.assertEqual(self.data, self.output.read_bytes())
                requests = [call.args[0] for call in self.opener.open.call_args_list]
                self.assertEqual([self.asset["browser_download_url"], self.asset["url"]],
                                 [request.full_url for request in requests])
                self.assertEqual("application/octet-stream", requests[1].get_header("Accept"))

    def test_interrupted_transfer_is_discarded_before_api_download(self):
        interrupted = Mock()
        interrupted.read.side_effect = [self.data[:5], http.client.IncompleteRead(b"partial")]
        interrupted.__enter__ = Mock(return_value=interrupted)
        interrupted.__exit__ = Mock(return_value=False)
        self.opener.open.side_effect = [interrupted, io.BytesIO(self.data)]
        downloader.download(self.release, self.output)
        self.assertEqual(self.data, self.output.read_bytes())

    def test_network_timeout_reset_and_dns_failures_allow_fallback(self):
        for error in (TimeoutError(), ConnectionResetError(),
                      urllib.error.URLError(TimeoutError()), urllib.error.URLError(socket.gaierror())):
            with self.subTest(error=type(error).__name__):
                self.opener.open.side_effect = [error, io.BytesIO(self.data)]
                downloader.download(self.release, self.output)
                self.assertEqual(self.data, self.output.read_bytes())

    def test_client_authentication_and_tls_errors_do_not_fallback(self):
        for error in (self.error(401), self.error(403), self.error(404),
                      urllib.error.URLError(ssl.SSLCertVerificationError())):
            with self.subTest(error=str(error)):
                self.opener.reset_mock()
                self.opener.open.side_effect = error
                with self.assertRaises(urllib.error.URLError):
                    downloader.download(self.release, self.output)
                self.opener.open.assert_called_once()
                self.assert_retained()

    def test_bad_primary_hash_or_size_fails_without_api_fallback(self):
        for data in (b"x" * len(self.data), self.data[:-1], self.data + b"extra"):
            with self.subTest(data=data):
                self.opener.reset_mock()
                self.opener.open.side_effect = None
                self.opener.open.return_value = io.BytesIO(data)
                with self.assertRaisesRegex(ValueError, "mismatch"):
                    downloader.download(self.release, self.output)
                self.opener.open.assert_called_once()
                self.assert_retained()

    def test_api_hash_failure_also_keeps_the_last_usable_file(self):
        self.opener.open.side_effect = [self.error(504), io.BytesIO(b"x" * len(self.data))]
        with self.assertRaisesRegex(ValueError, "hash mismatch"):
            downloader.download(self.release, self.output)
        self.assertEqual(2, self.opener.open.call_count)
        self.assert_retained()

    def test_failed_api_transfer_propagates_failure_and_cleans_up(self):
        for status in (403, 504):
            with self.subTest(status=status):
                self.opener.reset_mock()
                self.opener.open.side_effect = [self.error(504), self.error(status)]
                with self.assertRaises(urllib.error.HTTPError) as raised:
                    downloader.download(self.release, self.output)
                self.assertEqual(status, raised.exception.code)
                self.assertEqual(status == 504,
                                 retryable("".join(traceback.format_exception(raised.exception))))
                self.assertEqual(2, self.opener.open.call_count)
                self.assert_retained()

    def test_missing_verification_or_changed_identity_is_rejected_before_network(self):
        for changes in ({"digest": None}, {"digest": "sha256:unknown"}, {"size": 0},
                        {"size": True}, {"id": -1}, {"url": "https://example.invalid/asset"},
                        {"url": self.asset["url"] + "0"},
                        {"browser_download_url": self.asset["browser_download_url"].replace("v2.1.0", "v2.0.0")}):
            with self.subTest(changes=changes):
                release = copy.deepcopy(self.release)
                release["assets"][0].update(changes)
                with self.assertRaises(ValueError):
                    downloader.download(release, self.output)
                self.opener.open.assert_not_called()
                self.assert_retained()

    def test_ambiguous_or_missing_patch_asset_is_rejected(self):
        for assets in ([], [self.asset, self.asset]):
            with self.subTest(assets=len(assets)):
                with self.assertRaisesRegex(ValueError, "exactly one"):
                    downloader.download({"tag_name": "v2.1.0", "assets": assets}, self.output)
                self.opener.open.assert_not_called()
                self.assert_retained()


if __name__ == "__main__":
    unittest.main()
