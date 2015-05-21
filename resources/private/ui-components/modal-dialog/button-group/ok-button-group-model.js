LUPAPISTE.OkButtonGroupModel = function (params) {
  "use strict";
  var self = this;

  self.okTitle = util.getIn(params, ["okTitle"]) || "ok";

  var okFn = util.getIn(params, ["okTitle"]);

  self.ok = function() {
    if (_.isFunction(okFn)) {
      okFn();
    }
    hub.send("close-dialog");
  };
};
