// Model for presenting authorized parties (valtuutetut) and inviting
// new person and company parties.
//
// Note: currently this implementation is used only for "application
// level" authorization. The docgen invitations are still used by the
// old system (old style dialog and InviteModel). In the future both
// approaches are converged towards this model.
//
// See also:
//   PersonInviteModel: Person invitation model.
//
//   CompanyInviteBubbleModel: Company invitation model. The weird is
//   due to the fact that CompanyInvite was already taken by the old
//   (soon deprecated) implementation.
//
// Both invitation models send their values (dialog contents) via hub
// to this model. The ajax calls are done here and information
// (errors, waiting) are propagated to the invitation models via error
// and waiting observables.
LUPAPISTE.AuthorizedPartiesModel = function() {
  "use strict";
  var self = this;

  ko.utils.extend(self, new LUPAPISTE.ComponentBaseModel());

  // ---------------------------------------------------
  // Authorized table
  // ---------------------------------------------------

  function isGuest( s ) {
    return _.includes( ["guest", "guestAuthority"], s );
  }

  function application() {
    return lupapisteApp.models.application;
  }

  function hasAuth( id ) {
    return lupapisteApp.models.applicationAuthModel.ok( id );
  }

  var nameTemplate = _.template( "<%- firstName %> <%- lastName %> (<%- username %>)");

  self.nameInformation = function( role ) {
    return nameTemplate( ko.mapping.toJS( role ));
  };

  // Combines every role of the auth into one string:
  // Kirjoitusoikeus, Lausunnonantaja
  self.roleInformation = function( role ) {
    return _( role.roles )
           .reject( isGuest )
           .map( _.flow( loc, _.capitalize ))
           .value()
           .join( ", ");
  };

  self.inviterInformation = function( role ) {
    var info = "";
    var obj = ko.mapping.toJS( role );
    var inviter = obj.inviter || _.get( obj, "invite.inviter" );
    if( _.isObject( inviter )) {
      info = inviter.firstName + " " + inviter.lastName;
    }
    return info;
  };

  self.isNotOwner = function( role ) {
    return application().isNotOwner( role );
  };

  self.showRemove = function( role ) {
    return hasAuth( "remove-auth") && self.isNotOwner( role );
  };

  self.showSubscriptionStatus = function( role ) {
    return application().canSubscribe( role );
  };

  self.subscriptionOn = function( role ) {
    var unsub = role.unsubscribed || _.noop;
    return !unsub();
  };

  self.showInviteButton = self.disposedComputed( function() {
    return hasAuth( "invite-with-role");
  });

  self.error = ko.observable();
  self.waiting = ko.observable();

  self.authorizedParties = self.disposedComputed( function() {
   return _( application().roles() )
          .reject( function( role ) {
            // Guests are filtered only if the party does
            // not have any other roles. At least in theory,
            // it is possible that guest is also statementGiver,
            // for example.
            return isGuest( role.role()) && role.roles.length === 1;
          })
          .value();
  });

  function ajaxInvite( command, params ) {
    ajax.command(command, params )
    .pending( self.waiting )
    .success( function() {
      hub.send( "bubble-dialog::bubble-dialog", {id: "close all"});
      hub.send( "indicator", {style: "positive"});
      // It would be better to implement a service for authorized parties,
      // instead of repository.load
      repository.load(application().id());
    })
    .error( function( res ) {
      self.error( res.text );
    })
    .call();
  }

   self.addEventListener( "authorized",
                          "bubble-person-invite",
                          function( params ) {
                            ajaxInvite( "invite-with-role",
                                        _.defaults( params.invite,
                                                    {id: application().id(),
                                                     documentName: "",
                                                     documentId: "",
                                                     path: "",
                                                     role: "writer"} ));
                          } );

  self.addEventListener( "authorized",
                         "bubble-company-invite",
                         function( params ) {
                           ajaxInvite( "company-invite",
                                       _.defaults( params.invite,
                                                   {id: application().id()}));
  });

  // ---------------------------------------------------
  // Invite person
  // ---------------------------------------------------

  self.personBubble = ko.observable( false );

  self.togglePersonBubble = function() {
    self.personBubble( !self.personBubble());
  };

// ---------------------------------------------------
  // Invite company
  // ---------------------------------------------------

  self.companyBubble = ko.observable( false );

  self.toggleCompanyBubble = function() {
    self.companyBubble( !self.companyBubble());
  };
};
