// Assign an authority to the application.
LUPAPISTE.AuthoritySelectModel = function() {
  "use strict";
  var self = this;
  ko.utils.extend( self, new LUPAPISTE.ComponentBaseModel());

  function appId() {
    return lupapisteApp.models.application.id();
  }

  self.canEdit = self.disposedPureComputed( function() {
    return lupapisteApp.models.applicationAuthModel.ok( "assign-application");
  });


  // The latest selected value. The initial value NaN denotes
  // case where user has not made a new selection.
  var latest = NaN;

  self.oldAssignee = self.disposedComputed( function() {
    var old = appId()
          ? ko.mapping.toJS( lupapisteApp.models.application.authority())
          : {};
    // Old assignee change overrides the latest.
    latest = NaN;
    return old;
  });

  self.pending = ko.observable(false);

  self.authorities = ko.observableArray();

  self.fullName = function( obj ) {
    obj = _.defaults( ko.mapping.toJS( obj ), {firstName: "", lastName: ""});

    return obj.lastName + " " + obj.firstName;
  };

  function fetchAuthorities() {
    if( appId() && self.canEdit() ) {
      ajax.query("application-authorities", {id: appId()})
        .success(function(res) {
          var auths = res.authorities;
          var old = self.oldAssignee();

          // If the oldAssignee is no longer part of the organization,
          // she is still shown in the select but disabled. See also
          // disableAuthorities below.
          if( old.id && !_.find( res.authorities, {id: old.id}) ) {
            auths = _.flatten( [_.assign( old, {disabled: true} ),
                                auths]);
          }
          self.authorities( auths );
        })
        .pending(self.pending)
        .call();
    }
  }

  self.addHubListener( "application-model-updated", fetchAuthorities);
  // Initialization
  fetchAuthorities();

  self.disableAuthorities = function( option, item ) {
    if( item ) {
      // Disabled is true only if the old assignee has been removed
      // from the organization
      ko.applyBindingsToNode( option, {disable: item.disabled }, item );
    }
  };

  // The reader function is outside the assigneeId computed,
  // so it can be easily used from the write function.
  function readSelect() {
    return _.isNaN( latest ) ? self.oldAssignee().id : null;
  }

  // The select value is handled with writable computed in order to
  // have clear understanding when the user has made the selection.
  self.assigneeId = self.disposedComputed( {
    read: readSelect,
    write: function( value ) {
      // Null and undefined are considered equal.
      if( (value || readSelect()) && value !== readSelect() ) {
        latest = value;
        ajax.command("assign-application", {id: appId(), assigneeId: latest || ""})
        .processing(self.pending)
        .success(function() {
          // Full reload is needed. For example, hetus are shown
          // unmasked to the assigned authority.
          repository.load(appId(), self.pending);
          hub.send( "indicator", {style: "positive"});
        })
        .call();
      }
    }
  });
};
