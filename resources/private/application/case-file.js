(function() {
  "use strict";

  var model = function(params) {
    var self = this;
    self.application = params.application;
    self.caseFile = ko.observableArray();

    var fetchCaseFile = function() {
      ajax.query("case-file-data", {id: ko.unwrap(params.application.id), lang: loc.getCurrentLanguage()})
        .success(function (data) {
          self.caseFile(data.process);
        })
        .call();
    };
    fetchCaseFile();

    self.hasProcessMetadata = !_.isEmpty(ko.unwrap(self.application.processMetadata));
    self.showTosMetadata = ko.observable(false);

    self.toggleTosMetadata = function() {
      self.showTosMetadata(!self.showTosMetadata());
    };

    hub.subscribe("application-loaded", function() {
      fetchCaseFile();
    });
  };

  ko.components.register("case-file", {
    viewModel: model,
    template: {element: "case-file-template"}
  });

})();
