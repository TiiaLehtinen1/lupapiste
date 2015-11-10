LUPAPISTE.BulletinCommentBoxModel = function(params) {
  "use strict";
  var self = this;

  ko.utils.extend(self, new LUPAPISTE.ComponentBaseModel(params));

  self.bulletinId = params.bulletinId;
  self.versionId = params.versionId;
  self.comment = ko.observable();
  self.attachments = ko.observableArray([]);
  self.pending = ko.observable(false);

  self.isDisabled = ko.pureComputed(function() {
    return self.pending() || !self.comment();
  });

  self.fileChanged = function(data, event) {
    self.attachments.push(util.getIn(_.first($(event.target)), ["files", 0]));
  };

  self.addAttachment = function(data, event) {
    $(event.target).closest("form").find("input[type='file']").click();
  };

  self.removeAttachment = function(attachment) {
    self.attachments.remove(attachment);
  };

  self.sendComment = function(form) {
    hub.send("bulletinService::newComment", {commentForm: form, files: self.attachments()});
  };

  self.addEventListener("bulletinService", "commentProcessed", function(event) {
    if (event.status === "success") {
      self.comment("");
      self.attachments([]);
      hub.send("indicator", {style: "positive", message: "bulletin.comment.save.success"});
    } else {
      hub.send("indicator", {style: "negative", message: "bulletin.comment.save.failed"});
    }
  });

  self.addEventListener("bulletinService", "commentProcessing", function(event) {
    var state = event.state;
    self.pending(state === "pending");
  });
};