"""Loopback development shell; zero source admission, no runtime/fixture enable flags."""
from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).resolve().parent))
from esv_verify.__main__ import main
if __name__=='__main__':
    try:main()
    except BaseException:raise SystemExit(70)
