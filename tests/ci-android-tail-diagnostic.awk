#!/usr/bin/awk -f
NF && $1 !~ /^#/ {
  if ($1 == "look-removal") part = "look"
  if ($1 == "nano-banana") part = "nano"
  if ($1 == "e2-batches") part = "e2"
  if ($1 == "camera-pilot-usb-launch") part = "camera"
  if ($1 == "camera-tabs") part = "gallery"
  if ($1 == "sheets") part = "settings"
  mode_group = part
  if (part == "camera" && $1 ~ /^camera-pilot-sd-/) mode_group = "camera-sd"
  if (mode_group == group) print
}
