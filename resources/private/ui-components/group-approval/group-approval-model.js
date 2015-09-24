// Group (as opposed to document) level approval/reject mechanism
// and visualization.
// Parameters [optional]:
//  docModel: DocModel instance.
//  subSchema: Schema for the group.
//  path: Group path within the document schema.
//  model: model for the group
//  [remove]: {fun, testClass}. See resolveRemoveOptions in docmodel.js
//            for details.

LUPAPISTE.GroupApprovalModel = function( params ) {
  "use strict";
  var self = this;
  var APPROVED = "approved";
  var APPROVE  = "approve";
  var REJECTED = "rejected";
  var REJECT   = "reject";
  // Neutral status is only used in the front-end.
  var NEUTRAL  = "neutral";

  self.remove = params.remove || {};
  self.docModel = params.docModel;
  self.model = params.model;
  self.isApprovable = Boolean(params.subSchema.approvable) ;
  self.hasContents = params.remove || self.isApprovable;
  var meta = self.docModel.getMeta( params.path );
  var myApproval = ko.observable( meta ? meta._approved : null );

  // Approval status resolution
  var masterApproval = ko.observable();

  self.approval = ko.pureComputed( function() {
    var approval = self.docModel.safeApproval( self.model, myApproval);
    var master = masterApproval();
    if( master && master.value !== NEUTRAL && master.timestamp > approval.timestamp ) {
      approval = master;
    }
    return {value: approval.value, timestamp: approval.timestamp}
  });

  self.docModel.approvalHubSubscribe( function( data ) {
    if( !data.receiver || _.isEqual( data.receiver, params.path)) {
      masterApproval ( _.cloneDeep(data.approval) );
    }
  }, true);

  // UI
  self.showStatus = ko.pureComputed( _.partial( self.docModel.isApprovalCurrent,
                                                self.model,
                                                self.approval ));

  self.isApproved = ko.pureComputed(_.partial (self.docModel.approvalStatus,
                                               self.approval,
                                               APPROVED));
  self.isRejected = ko.pureComputed(_.partial (self.docModel.approvalStatus,
                                               self.approval,
                                               REJECTED));

  self.testId = _.partial( self.docModel.approvalTestId, params.path );

  function showButton( operation, excluder ) {
    return self.isApprovable
        && self.docModel.authorizationModel.ok( operation + "-doc")
        && (!excluder() || !self.showStatus());
  }

  self.showReject  = ko.pureComputed(_.partial( showButton, REJECT, self.isRejected ));
  self.showApprove = ko.pureComputed(_.partial( showButton, APPROVE, self.isApproved ));

  function changeStatus( flag ) {
    self.docModel.updateApproval( params.path,
                                  flag,
                                  function( approval ) {
                                    myApproval( approval );
                                  } )

  }

  self.reject  = _.partial( changeStatus, false );
  self.approve = _.partial( changeStatus, true );

  self.details = ko.pureComputed( _.partial( self.docModel.approvalInfo,
                                             myApproval ));

  // Send the initial approval status to the master.
  self.docModel.approvalHubSend( self.docModel.safeApproval( self.model, myApproval),
                                 params.path );

}
