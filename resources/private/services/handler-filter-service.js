LUPAPISTE.HandlerFilterService = function(applicationFiltersService) {
  "use strict";
  var self = this;

  self.selected = ko.observableArray([]);

  // dummy elements for autocomplete
  self.noAuthority = {id: "no-authority", fullName: "Ei käsittelijää", behaviour: "singleSelection"};
  self.all = {id: "all", fullName: "Kaikki", behaviour: "clearSelected"};

  var defaultFilter = ko.pureComputed(function() {
    var applicationFilters = _.find(applicationFiltersService.savedFilters(), function(f) {
      return f.isDefaultFilter();
    });
    return util.getIn(applicationFilters, ["filter", "handlers"]) || [];
  });

  var savedFilter = ko.pureComputed(function() {
    return util.getIn(applicationFiltersService.selected(), ["filter", "handlers"]);
  });

  var usersInSameOrganizations = ko.observable();

  ko.computed(function() {
    if (savedFilter() && _.contains(savedFilter(), "no-authority")) {
      self.selected([self.noAuthority]);
    } else if (!savedFilter() && _.contains(defaultFilter(), "no-authority")) {
      self.selected([self.noAuthority]);
    } else {
      self.selected(_.filter(usersInSameOrganizations(),
        function (user) {
          if (savedFilter()) {
            return _.contains(savedFilter(), user.id);
          } else {
            return _.contains(defaultFilter(), user.id);
          }
        }));
    }
  });

  self.data = ko.pureComputed(function() {
    return usersInSameOrganizations();
  });

  function mapUser(user) {
    user.fullName = _.filter([user.lastName, user.firstName]).join("\u00a0");
    return user;
  }

  ajax
    .query("users-in-same-organizations")
    .error(_.noop)
    .success(function(res) {
      usersInSameOrganizations(_(res.users).map(mapUser).sortBy("fullName").value());
    })
    .call();
};
