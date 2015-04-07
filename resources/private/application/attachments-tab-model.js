LUPAPISTE.AttachmentsTabModel = function(appModel, signingModel, verdictAttachmentPrintsOrderModel) {
  "use strict";

  var self = this;

  self.authorizationModel = lupapisteApp.models.applicationAuthModel;
  self.appModel = appModel;
  self.signingModel = signingModel;
  self.verdictAttachmentPrintsOrderModel = verdictAttachmentPrintsOrderModel;

  self.preAttachmentsByOperation = ko.observableArray();
  self.postAttachmentsByOperation = ko.observableArray();
  self.showHelp = ko.observable(false);
  self.attachmentsOperation = ko.observable();
  self.attachmentsOperations = ko.observable([]);

  function unsentAttachmentFound(attachments) {
    return _.some(attachments, function(a) {
      var lastVersion = _.last(a.versions);
      return lastVersion &&
             (!a.sent || lastVersion.created > a.sent) &&
             (!a.target || (a.target.type !== "statement" && a.target.type !== "verdict"));
    });
  }

  var attachmentsOperationsMapping = {
      "attachmentsAdd": {
        loc: loc("application.attachmentsAdd"),
        clickCommand: function() {
          return self.newAttachment();
        },
        visibleFn: function (rawAttachments) {
          return self.authorizationModel.ok("upload-attachment");
        }
      },
      "attachmentsCopyOwn": {
        loc: loc("application.attachmentsCopyOwn"),
        clickCommand: function() {
          return self.copyOwnAttachments();
        },
        visibleFn: function (rawAttachments) {
          return self.authorizationModel.ok("copy-user-attachments-to-application");
        }
      },
      "newAttachmentTemplates": {
        loc: loc("application.newAttachmentTemplates"),
        clickCommand: function() {
          return self.attachmentTemplatesModel.show();
        },
        visibleFn: function (rawAttachments) {
          return self.authorizationModel.ok("create-attachments");
        }
      },
      "stampAttachments": {
        loc: loc("application.stampAttachments"),
        clickCommand: function() {
          return self.startStamping();
        },
        visibleFn: function (rawAttachments) {
          return self.authorizationModel.ok("stamp-attachments") && self.appModel.hasAttachment();
        }
      },
      "markVerdictAttachments": {
        loc: loc("application.markVerdictAttachments"),
        clickCommand: function() {
          return self.startMarkingVerdictAttachments();
        },
        visibleFn: function (rawAttachments) {
          return self.authorizationModel.ok("set-attachments-as-verdict-attachment") && self.appModel.hasAttachment();
        }
      },
      "orderVerdictAttachments": {
        loc: loc("verdict.orderAttachmentPrints.button"),
        clickCommand: function() {
          self.verdictAttachmentPrintsOrderModel.openDialog({application: self.appModel});
        },
        visibleFn: function (rawAttachments) {
          return self.authorizationModel.ok('order-verdict-attachment-prints') && self.verdictAttachmentPrintsOrderModel.attachments().length;
        }
      },
      "signAttachments": {
        loc: loc("application.signAttachments"),
        clickCommand: function() {
          return self.signingModel.init(self.appModel);
        },
        visibleFn: function (rawAttachments) {
          return self.authorizationModel.ok("sign-attachments") && self.appModel.hasAttachment();
        }
      },
      "attachmentsMoveToBackingSystem": {
        loc: loc("application.attachmentsMoveToBackingSystem"),
        clickCommand: function() {
          return self.startMovingAttachmentsToBackingSystem();
        },
        visibleFn: function (rawAttachments) {
          return self.authorizationModel.ok("move-attachments-to-backing-system") && self.appModel.hasAttachment() && unsentAttachmentFound(rawAttachments);
        }
      },
      "attachmentsMoveToCaseManagement": {
        loc: loc("application.attachmentsMoveToCaseManagement"),
        clickCommand: function() {
          return self.startMovingAttachmentsToCaseManagement();
        },
        visibleFn: function (rawAttachments) {
          return self.authorizationModel.ok("attachments-to-asianhallinta") && self.appModel.hasAttachment() && unsentAttachmentFound(rawAttachments);
        }
      },
      "downloadAll": {
        loc: loc("download-all"),
        clickCommand: function() {
          window.location = "/api/raw/download-all-attachments?id=" + self.appModel.id() + "&lang=" + loc.getCurrentLanguage();
        },
        visibleFn: function (rawAttachments) {
          return self.appModel.hasAttachment();
        }
      }
  };

  function updatedAttachmentsOperations(rawAttachments) {
    var commands = [];
    _.forEach(_.keys(attachmentsOperationsMapping), function(k) {
      var operationData = attachmentsOperationsMapping[k];
      if (!operationData) { error("Invalid attachment operations action: " + k); return [];}
      if (!operationData.visibleFn) { error("No visibility resolving method defined for attachment operations action: " + k); return [];}

      var isOptionVisible = (self.authorizationModel) ? operationData.visibleFn(rawAttachments) : false;
      if (isOptionVisible) {
        commands.push({name: k, loc: operationData.loc});
      }
    });
    return commands;
  }

  self.attachmentsOperation.subscribe(function(opName) {
    if (opName) {
      attachmentsOperationsMapping[opName].clickCommand();
      self.attachmentsOperation(undefined);
    }
  });

  self.toggleHelp = function() {
    self.showHelp(!self.showHelp());
  };

  self.refresh = function(appModel) {
    self.appModel = appModel;

    var rawAttachments = ko.mapping.toJS(appModel.attachments);
    var preAttachments = attachmentUtils.getPreAttachments(rawAttachments);
    var postAttachments = attachmentUtils.getPostAttachments(rawAttachments);

    // pre verdict attachments are not editable after verdict has been given
    var preGroupEditable = currentUser.isAuthority() || !_.contains(LUPAPISTE.config.postVerdictStates, appModel.state());
    var preGrouped = attachmentUtils.getGroupByOperation(preAttachments, preGroupEditable, self.appModel.allowedAttachmentTypes());

    var postGrouped = attachmentUtils.getGroupByOperation(postAttachments, true, self.appModel.allowedAttachmentTypes());

    if (self.authorizationModel.ok("set-attachment-not-needed")) {
      // The "not needed" functionality is only enabled for attachments in pre-verdict state, so here only going through "preGrouped"
      var attArrays = _.pluck(preGrouped, "attachments");
      _.each(attArrays, function(attArray) {
        _.each(attArray, function(att) {

          // reload application also when error occurs so that model and db dont get out of sync
          att.notNeeded.subscribe(function(newValue) {
            ajax.command("set-attachment-not-needed", {id: self.appModel.id(), attachmentId: att.id, notNeeded: newValue})
            .success(self.appModel.reload)
            .error(self.appModel.reload)
            .processing(self.appModel.processing)
            .call();
          });

        });
      });
    }

    self.preAttachmentsByOperation(preGrouped);
    self.postAttachmentsByOperation(postGrouped);
    self.attachmentsOperation(undefined);
    self.attachmentsOperations(updatedAttachmentsOperations(rawAttachments));
  };

  self.startMovingAttachmentsToBackingSystem = function() {
    hub.send("start-moving-attachments-to-backing-system");
  };

  self.startMovingAttachmentsToCaseManagement = function() {
    hub.send("start-moving-attachments-to-case-management");
  };

  self.newAttachment = function() {
    attachment.initFileUpload({
      applicationId: self.appModel.id(),
      attachmentId: null,
      attachmentType: null,
      typeSelector: true,
      opSelector: true
    });
    LUPAPISTE.ModalDialog.open("#upload-dialog");
  };

  self.copyOwnAttachments = function() {
    var doSendAttachments = function() {
      ajax.command("copy-user-attachments-to-application", {id: self.appModel.id()})
        .success(self.appModel.reload)
        .processing(self.appModel.processing)
        .call();
      return false;
    };
    LUPAPISTE.ModalDialog.showDynamicYesNo(
      loc("application.attachmentsCopyOwn"),
      loc("application.attachmentsCopyOwn.confirmationMessage"),
      {title: loc("yes"), fn: doSendAttachments},
      {title: loc("no")}
    );
  };

  self.deleteSingleAttachment = function(a) {
    var attId = _.isFunction(a.id) ? a.id() : a.id;
    var doDelete = function() {
      ajax.command("delete-attachment", {id: self.appModel.id(), attachmentId: attId})
        .success(function() {
          self.appModel.reload();
        })
        .error(function (e) {
          LUPAPISTE.ModalDialog.showDynamicOk(loc("error.dialog.title"), loc(e.text));
        })
        .processing(self.appModel.processing)
        .call();
      return false;
    };
    LUPAPISTE.ModalDialog.showDynamicYesNo(
      loc("attachment.delete.header"),
      loc("attachment.delete.message"),
      {title: loc("yes"), fn: doDelete},
      {title: loc("no")});
  };

  self.startStamping = function() {
    hub.send("start-stamping", {application: self.appModel});
  };

  self.startMarkingVerdictAttachments = function() {
    hub.send("start-marking-verdict-attachments", {application: self.appModel});
  };

  self.attachmentTemplatesModel = new function() {
    var templateModel = this;
    templateModel.ok = function(ids) {
      ajax.command("create-attachments", {id: self.appModel.id(), attachmentTypes: ids})
        .success(function() { repository.load(self.appModel.id()); })
        .complete(LUPAPISTE.ModalDialog.close)
        .call();
    };

    templateModel.init = function() {
      templateModel.selectm = $("#dialog-add-attachment-templates .attachment-templates").selectm();
      templateModel.selectm.ok(templateModel.ok).cancel(LUPAPISTE.ModalDialog.close);
      return templateModel;
    };

    templateModel.show = function() {
      var data = _.map(self.appModel.allowedAttachmentTypes(), function(g) {
        var groupId = g[0];
        var groupText = loc(["attachmentType", groupId, "_group_label"]);
        var attachemntIds = g[1];
        var attachments = _.map(attachemntIds, function(a) {
          var id = {"type-group": groupId, "type-id": a};
          var text = loc(["attachmentType", groupId, a]);
          return {id: id, text: text};
        });
        return [groupText, attachments];
      });
      templateModel.selectm.reset(data);
      LUPAPISTE.ModalDialog.open("#dialog-add-attachment-templates");
      return templateModel;
    };
  }();

  hub.subscribe("op-description-changed", function(e) {
    var opid = e["op-id"];
    var desc = e["op-desc"];

    _.each(self.appModel.attachments(), function(attachment) {
      if ( ko.unwrap(attachment.op) && attachment.op.id() === opid ) {
        attachment.op.description(desc);
      }
    });

    self.refresh(self.appModel);
  });

};
