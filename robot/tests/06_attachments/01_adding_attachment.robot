*** Settings ***

Documentation  Mikko adds an attachment
Suite setup     Mikko logs in
Suite teardown  Logout
Resource       ../../common_resource.txt
Variables      variables.py

*** Test Cases ***

Mikko goes to empty attachments tab
  Wait until page contains element  test-application-link
  Click element    test-application-link
  Wait until page contains element  application-page-is-ready
  Click element    test-attachments-tab

Mikko adds txt attachment
  Add attachment  ${TXT_TESTFILE_PATH}  ${TXT_TESTFILE_DESCRIPTION}

*** Keywords ***

Attachment count is
  [Arguments]  ${amount}
  Xpath Should Match X Times  //div[@id='attachments_tab']//tbody/tr  ${amount}

Add attachment
  [Arguments]  ${path}  ${description}
  Click element    add-attachment
  Select Frame     uploadFrame
  Wait until       Element should be visible  test-save-new-attachment
  Wait until       Page should contain element  xpath=//form[@id='attachmentUploadForm']//option[@value='muut.muu']
  Select From List  attachmentType  muut.muu
  Input text       text  ${description}
  Choose File      xpath=//form[@id='attachmentUploadForm']/input[@type='file']  ${path}
  Click element    test-save-new-attachment
  Unselect Frame
  Wait Until Page Contains  Muu liite

Open attachment and check content
  [Arguments]  ${url}  ${content}
  ## Open the link using JavaScript into the same window.
  ## Switching between windows is problematic (browser versions, mac/win...)
  Execute Javascript  window.location = '${url}'
  Wait Until Page Contains  ${content}
  ## Get back to application for logout
  Go Back
  Wait Until Page Contains  Muu liite

Open attachment preview page
  [Arguments]  ${filename}
  Click element  test-attachments-tab
  Click element  test-attachment-type-link
  ## This reload is a hack that is needed only for tests
  Execute Javascript  window.location.reload()
  Wait Until Page Contains  ${filename}

