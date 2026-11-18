import contextlib
import importlib.util
import io
from pathlib import Path
import sys
import tempfile
from unittest.mock import Mock, patch

SCRIPTS=Path(__file__).resolve().parents[2]/'integrations/hermes/open-pocket/scripts'
sys.path.insert(0,str(SCRIPTS))
import notify
sys.path.pop(0)


def test_arrivals_are_batched_and_not_repeated():
    with tempfile.TemporaryDirectory() as directory:
        client=Mock();client.cache=Path(directory)
        client.request.return_value={'jobs':[{'id':'a'},{'id':'b'}],'next_after':None}
        with patch.object(notify,'Client',return_value=client):
            output=io.StringIO()
            with contextlib.redirect_stdout(output):notify.main()
            assert '2 new recordings' in output.getvalue()
            assert (client.cache/'announced.json').stat().st_mode & 0o777 == 0o600
            output=io.StringIO()
            with contextlib.redirect_stdout(output):notify.main()
            assert output.getvalue()==''


def test_failed_lookup_does_not_reserve_arrival():
    with tempfile.TemporaryDirectory() as directory:
        client=Mock();client.cache=Path(directory);client.request.side_effect=OSError()
        with patch.object(notify,'Client',return_value=client):
            try:notify.main()
            except OSError:pass
            else:raise AssertionError('Expected failure')
            assert not (client.cache/'announced.json').exists()
