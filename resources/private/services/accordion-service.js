//
// Provides services for accordion-toolbar component.
//
//
LUPAPISTE.AccordionService = function() {
  "use strict";
  var self = this;

  self.appModel = lupapisteApp.models.application;
  self.authModel = lupapisteApp.models.applicationAuthModel;
  self.indicator = ko.observable({});
  ko.computed(function() {
    var resultType = self.indicator().type;
    if (resultType === "saved") {
      hub.send("indicator", {style: "positive"});
    } else if (resultType === "err") {
      hub.send("indicator", {style: "negative"});
    }
  });

  self.documents = ko.observable();

  self.setDocuments = function(docs) {
    var docsWithOperation = _.filter(docs, function(doc) {
      return doc["schema-info"].op;
    });
    self.documents(_.map(docsWithOperation, function(doc) {
      return {docId: doc.id, operation: ko.mapping.fromJS(doc["schema-info"].op), schema: doc.schema, data: doc.data};
    }));
  };

  self.accordionFields = ko.observableArray([]);
  self.documents.subscribe(function() {
    var identifiers = _(self.documents())
      .filter(function(doc) {
        return _.find(doc.schema.body, "identifier"); // finds first
      })
      .map(function(doc) {
        var subSchema = _.find(doc.schema.body, "identifier");
        var key = _.get(subSchema, "name");
        return {docId: doc.docId, schema: subSchema, key: key, value: ko.observable(util.getIn(doc, ["data", key, "value"]))};
      })
      .value();
    self.accordionFields(identifiers); // set the fields for each document
  });

  self.getOperation = function(docId) {
    return util.getIn(_.find(self.documents(), {docId: docId}), ["operation"]);
  };

  self.getOperationByOpId = function(opId) {
    return util.getIn(_.find(self.documents(), function(doc) {
      return doc.operation.id && doc.operation.id() === opId;
    }), ["operation"]);
  };

  self.getIdentifier = function(docId) {
    return _.find(self.accordionFields(), {docId: docId});
  };

  self.toggleEditors = function(show) {
    hub.send("accordionToolbar::toggleEditor", {show: show});
  };

  hub.subscribe("accordionService::saveIdentifier", function(event) {
    var docId = event.docId;
    var value = event.value;
    var path = [event.key];
    var doc = _.find(self.accordionFields(), {docId: docId});
    if (doc.value() !== value) {
      lupapisteApp.services.documentDataService.updateDoc(docId, [[path, value]], self.indicator);
      doc.value(value);
    }
  });

  hub.subscribe("accordionService::saveOperationDescription", function(event) {
    var appId = event.appId;
    var operationId = event.operationId;
    var value = event.description;
    var operation = self.getOperationByOpId(operationId);
    if (operation.description() !== value) {
      ajax.command ("update-op-description", {id: appId,
                                              "op-id": operationId,
                                              desc: value})
      .success (function(resp) {
        operation.description(value);
        hub.send("op-description-changed", {appId: appId,
                                            "op-id": operationId,
                                            "op-desc": value});
        util.showSavedIndicator(resp);
      })
      .call();
    }
  });

};