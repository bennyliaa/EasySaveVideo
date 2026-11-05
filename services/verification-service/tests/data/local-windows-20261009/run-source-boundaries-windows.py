"""Run unchanged source guard with Windows extended-path file opens."""
import builtins, os, pathlib, runpy
original_open = pathlib.Path.open
def long_path_open(self, *args, **kwargs):
    if os.name == 'nt' and self.is_absolute():
        name = str(self)
        if not name.startswith('\\\\?\\'):
            name = '\\\\?\\' + name
        return builtins.open(name, *args, **kwargs)
    return original_open(self, *args, **kwargs)
pathlib.Path.open = long_path_open
runpy.run_path('scripts/check-boundaries.py', run_name='__main__')
