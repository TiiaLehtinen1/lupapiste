LUPAPISTE.BulletinCommentsModel = function(params) {
  "use strict";
  var self = this;

  ko.utils.extend(self, new LUPAPISTE.ComponentBaseModel(params));

  self.showVersionComments = params.showVersionComments;

  self.bulletin = params.bulletin

  self.comments = params.comments;

  self.commentsLeft = ko.pureComputed(function() {
    return params.commentsLeft() > 0;
  });

  self.totalComments = params.totalComments;

  self.asc = ko.observable(false);

  self.fetchComments = _.debounce(function() {
    var bulletinId = util.getIn(self, ["bulletin", "id"]);
    var versionId = util.getIn(self, ["showVersionComments", "id"]);
    self.sendEvent("publishBulletinService", "fetchBulletinComments", {bulletinId: bulletinId,
                                                                       versionId: versionId,
                                                                       asc: self.asc()});
  }, 50);

  ko.computed(function() {
    self.asc();
    self.fetchComments();
  })

  self.hideComments = function() {
    self.showVersionComments(undefined);
  };

  self.proclaimedHeader = ko.pureComputed(function() {
    var start  = util.getIn(self, ["showVersionComments", "proclamationStartsAt"], "");
    var end    = util.getIn(self, ["showVersionComments", "proclamationEndsAt"], "");
    if (start && end) {
      return loc("bulletin.proclaimedHeader.duringProclamation") + " " + moment(start).format("D.M.YYYY") + " - " + moment(end).format("D.M.YYYY") +
        " " + loc("bulletin.proclaimedHeader.givenComments") + " " + self.totalComments() + " kpl."
    }
  });

  self.sortButtonText = ko.pureComputed(function() {
    if (self.asc()) {
      return "bulletin.comments.sort.asc";
    } else {
      return "bulletin.comments.sort.desc";
    }
  });
};
