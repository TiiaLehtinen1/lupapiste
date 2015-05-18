(function() {
  "use strict";

  function loadTimingData() {
    if (!features.enabled("perfmon") || !window.performance) {
      return;
    }

    if (!window.performance.timing.loadEventEnd) {
      setTimeout(loadTimingData, 10);
      return;
    }

    var table = $("footer table.dev-debug-timing");
    var data = [["fetch", "fetchStart", "requestStart"],
                ["req", "requestStart", "responseStart"],
                ["resp", "responseStart", "responseEnd"],
                ["network", "fetchStart", "responseEnd"],
                ["display", "responseEnd", "loadEventEnd"],
                ["total", "navigationStart", "loadEventEnd"]];

    _.each(data, function(row) {
      var name = row[0],
          start = window.performance.timing[row[1]],
          end = window.performance.timing[row[2]],
          duration = end - start;
      if (typeof start !== "number") {throw "Unknown timineg event: " + row[1]; }
      if (typeof end !== "number") {throw "Unknown timineg event: " + row[2]; }
      table
        .append($("<tr>").css("padding", "0px")
          .append($("<td>").text(name).css("padding", "0px"))
          .append($("<td>").text(duration).css("padding", "0px").css("text-align","right")));
    });

    ajax.post(window.location.protocol + "//" + window.location.host + "/api/perfmon/browser-timing")
      .raw()
      .json({timing: window.performance.timing})
      .header("npm", "true")
      .call();
  }

  hub.subscribe("features-loaded", loadTimingData, true);
})();
