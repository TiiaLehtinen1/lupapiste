*** Settings ***

Documentation   Bulletin page
Suite teardown  Logout
Resource        ../../common_resource.robot
Resource        ./27_common.robot

*** Test Cases ***

Init Bulletins
  Create bulletins  2
  Go to bulletins page

Bulletin page should have docgen data
  Open bulletin by index  1

  Element should be visible  bulletinDocgen
  ${sectionCount}=  Get Matching Xpath Count  //div[@id='bulletin-component']//div[@id='bulletinDocgen']/section
  Should Be True  ${sectionCount} > 0

State is visible
  Bulletin state is  published
