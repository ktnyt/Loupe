"""Validate an exported, checkpointed gallery-index.db without changing it."""
import sqlite3
import sys
from pathlib import Path
connection = sqlite3.connect(Path(sys.argv[1]).resolve().as_uri() + '?mode=ro', uri=True)
assert connection.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
photos = connection.execute('SELECT count(*) FROM photos').fetchone()[0]
assert photos == connection.execute('SELECT coalesce(sum(count),0) FROM albums').fetchone()[0]
assert connection.execute('SELECT count(*) FROM (SELECT bundle FROM photos GROUP BY bundle HAVING count(distinct folder)>1)').fetchone()[0] == 0
assert connection.execute('SELECT count(*) FROM albums a LEFT JOIN photos p ON a.cover=p.ordinal WHERE p.ordinal IS NULL OR a.folder!=p.folder').fetchone()[0] == 0
assert connection.execute('SELECT count(*) FROM albums a WHERE a.count!=(SELECT count(*) FROM photos p WHERE p.folder=a.folder)').fetchone()[0] == 0
print('Index integrity, album counts, covers and burst isolation passed:', photos, 'photos')
