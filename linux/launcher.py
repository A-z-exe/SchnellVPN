import sys
from pathlib import Path

# The client package lives at the repository root; win it over when running
# from a source checkout (frozen builds get it from the bundled archive).
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from schnellvpn.app import main

if __name__ == "__main__":
    raise SystemExit(main())
