# Notices to add for the Ubuntu/proot feature

Add to NOTICE.md and docs/LEGAL_NOTICES.md (NoticesTest requires every shipped binary to be named):

- **proot** (libproot.so, libproot-loader.so): GPL-2.0-or-later. Source: https://github.com/termux/proot
  (the Termux build). The existing written offer of source and licences/COPYING.GPLv2 cover it.
- **talloc** (libtalloc.so): LGPL-3.0-or-later. Source: https://talloc.samba.org
  Covered by licences/COPYING.LGPLv3.
- **Ubuntu base image**: downloaded by the user at runtime from cdimage.ubuntu.com, not redistributed in the APK.
