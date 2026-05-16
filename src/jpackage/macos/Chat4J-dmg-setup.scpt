tell application "Finder"
  set volumeUrl to "DEPLOY_VOLUME_URL"
  set volumePath to "DEPLOY_VOLUME_PATH"
  set targetName to "DEPLOY_TARGET"
  set backgroundFile to "DEPLOY_BG_FILE"
  set installLocation to "DEPLOY_INSTALL_LOCATION"
  set installDisplayName to "DEPLOY_INSTALL_LOCATION_DISPLAY_NAME"

  set theDisk to missing value
  repeat with attempt from 1 to 20
    set matchingDisks to disks whose URL is volumeUrl
    if (count of matchingDisks) is greater than 0 then
      set theDisk to item 1 of matchingDisks
      exit repeat
    end if
    delay 0.25
  end repeat

  if theDisk is missing value then
    error "Mounted DMG volume not found: " & volumeUrl
  end if

  open theDisk
  delay 1

  set theWindow to container window of theDisk
  set current view of theWindow to icon view

  try
    set toolbar visible of theWindow to false
  end try
  try
    set statusbar visible of theWindow to false
  end try

  set the bounds of theWindow to {400, 100, 920, 440}

  set theViewOptions to icon view options of theWindow
  set arrangement of theViewOptions to not arranged
  set icon size of theViewOptions to 128

  try
    set background picture of theViewOptions to POSIX file backgroundFile
  end try

  if not (exists item installDisplayName of theDisk) then
    make new alias file at POSIX file volumePath to POSIX file installLocation with properties {name:installDisplayName}
  end if

  delay 0.5

  if exists item targetName of theWindow then
    set position of item targetName of theWindow to {120, 130}
  end if

  if exists item installDisplayName of theWindow then
    set position of item installDisplayName of theWindow to {390, 130}
  end if

  update theDisk without registering applications
  delay 2
  close theWindow
end tell
