;(function($) {
  "use strict";

  function nop() { }

  function Tree(context, args) {
    var self = this;

    args = args || {};
    
    var defaultTemplate = args.template || $(".default-tree-template");
    var titleTemplate = args.title || $(".tree-title", defaultTemplate);
    var contentTemplate = args.content || $(".tree-content", defaultTemplate);
    var navTemplate = args.nav || $(".tree-nav", defaultTemplate);
    
    self.linkTemplate = args.link || $(".tree-link", defaultTemplate);
    self.lastTemplate = args.last || $(".tree-last", defaultTemplate);

    self.onSelect = args.onSelect || nop;
    self.baseModel = args.baseModel || {};
    self.data = [];
    self.width = args.width || context.width();
    self.speed = args.speed || self.width / 2;
    self.moveLeft  = {"margin-left": "-=" + self.width};
    self.moveRight = {"margin-left": "+=" + self.width};
    
    function findTreeData(target) {
      var data = target.data("tree-link-data");
      if (data) return data;
      var parent = target.parent();
      return (parent.length) ? findTreeData(parent) : null;
    }

    self.clickGo = function(e) {
      var target = $(e.target),
          link = findTreeData(target);
      if (!link) return false;
      var selectedLink = link[0],
          nextElement = link[1],
          next = _.isArray(nextElement) ? self.makeLinks(nextElement) : self.makeFinal(nextElement);
      self.model.stack.push(selectedLink);
      self.stateNop().content.append(next).animate(self.moveLeft, self.speed, self.stateGo);
      return false;
    };
    
    self.goBack = function() {
      if (self.model.stack().length < 1) return false;
      if (self.model.selected()) {
        self.model.selected(null);
        self.onSelect(null);
      }
      self.stateNop();
      self.model.stack.pop();
      self.content.animate(self.moveRight, self.speed, function() {
        self.stateGo();
        $(".tree-page", self.content).filter(":last").remove();
      });
      return self;
    };
    
    self.setClickHandler = function(handler) { self.clickHandler = handler; return self; }
    self.stateGo = _.partial(self.setClickHandler, self.clickGo);
    self.stateNop = _.partial(self.setClickHandler, nop);

    self.makeFinal = function(data) {
      self.model.selected(data);
      self.onSelect(data);
      return self.lastTemplate
        .clone()
        .addClass("tree-page")
        .css("width", self.width + "px")
        .applyBindings(_.extend({}, self.baseModel, data));
    }
    
    self.makeLinks = function(data) {
      return _.reduce(data, self.appendLink, $("<div>").addClass("tree-page").css("width", self.width + "px"));
    };
    
    self.appendLink = function(div, linkData) {
      var link = self.linkTemplate
        .clone()
        .data("tree-link-data", linkData)
        .applyBindings(_.extend({}, self.baseModel, linkData[0]));
      return div.append(link);
    };
    
    self.reset = function(data) {
      self.stateNop();
      self.data = data;
      self.model.stack.removeAll();
      self.content
        .empty()
        .css("margin-left", "" + self.width + "px")
        .append(self.makeLinks(data))
        .animate(self.moveLeft, self.speed, self.stateGo);
      return self;
    };
    
    self.model = {
      stack: ko.observableArray([]),
      selected: ko.observable(),
      goBack: self.goBack,
      goStart: function() { self.reset(self.data); return false; }
    };

    self.content = contentTemplate.clone().click(function(event) {
      var e = getEvent(event);
      e.preventDefault();
      e.stopPropagation();
      self.clickHandler(e);
      return false;
    });
    
    context
      .append(titleTemplate.clone())
      .append(self.content)
      .append(navTemplate.clone())
      .applyBindings(_.extend({}, self.baseModel, self.model));
    
    return util.fluentify({
      reset:    function(data) { self.reset(data); },
      back:     function() { self.goBack(); },
      selected: self.model.selected
    });
    
  }
  
  $.fn.selectTree = function(arg) {
    return new Tree(this, arg);
  };

})(jQuery);
