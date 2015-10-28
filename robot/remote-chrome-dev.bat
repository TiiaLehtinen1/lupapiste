@set target=%1
@if [%target%]==[] set target=tests

@set server=%2
@if [%server%]==[] set server=https://www-dev.lupapiste.fi

call remote-config.bat

pybot -d target --exclude integration --exclude fail --RunEmptySuite --variable BROWSER:chrome --variable SELENIUM:http://%remote%:4444/wd/hub --variable SERVER:%server% %target%
