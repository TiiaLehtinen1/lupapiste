LUPAPISTE.OkDialogModel = function (params) {
  "use strict";
  var self = this;

  self.localize = _.isUndefined(params.localize) ? true : params.localize;

  self.text = params.text;

  self.ok = params.okFn || function() { _.noop(); };
};