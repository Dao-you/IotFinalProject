import os
import sys


def enable_system_dbus_path():
    dist_packages = "/usr/lib/python3/dist-packages"
    if os.path.isdir(dist_packages) and dist_packages not in sys.path:
        sys.path.append(dist_packages)
