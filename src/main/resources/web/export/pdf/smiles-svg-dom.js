(function () {
  function escapeText(value) {
    return String(value == null ? "" : value)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;");
  }

  function escapeAttribute(value) {
    return escapeText(value).replace(/"/g, "&quot;");
  }

  function TextNode(value) {
    this.value = String(value == null ? "" : value);
    this.parentNode = null;
  }

  Object.defineProperty(TextNode.prototype, "textContent", {
    get: function () {
      return this.value;
    },
    set: function (value) {
      this.value = String(value == null ? "" : value);
    },
  });

  Object.defineProperty(TextNode.prototype, "outerHTML", {
    get: function () {
      return escapeText(this.value);
    },
  });

  function ElementNode(name) {
    this.nodeName = String(name);
    this.attributes = Object.create(null);
    this.childNodes = [];
    this.parentNode = null;
    this.style = Object.create(null);
  }

  Object.defineProperty(ElementNode.prototype, "firstChild", {
    get: function () {
      return this.childNodes.length === 0 ? null : this.childNodes[0];
    },
  });

  Object.defineProperty(ElementNode.prototype, "children", {
    get: function () {
      return this.childNodes.filter(function (child) {
        return child instanceof ElementNode;
      });
    },
  });

  Object.defineProperty(ElementNode.prototype, "textContent", {
    get: function () {
      return this.childNodes
        .map(function (child) {
          return child.textContent;
        })
        .join("");
    },
    set: function (value) {
      this.childNodes = [];
      this.appendChild(new TextNode(value));
    },
  });

  ElementNode.prototype.appendChild = function (child) {
    if (child.parentNode) {
      child.parentNode.removeChild(child);
    }
    child.parentNode = this;
    this.childNodes.push(child);
    return child;
  };

  ElementNode.prototype.removeChild = function (child) {
    var index = this.childNodes.indexOf(child);
    if (index < 0) {
      throw new Error("Node is not a child");
    }
    this.childNodes.splice(index, 1);
    child.parentNode = null;
    return child;
  };

  ElementNode.prototype.setAttribute = function (name, value) {
    this.attributes[String(name)] = String(value);
  };

  ElementNode.prototype.setAttributeNS = function (namespace, name, value) {
    this.setAttribute(name, value);
  };

  ElementNode.prototype.getAttribute = function (name) {
    var key = String(name);
    return Object.prototype.hasOwnProperty.call(this.attributes, key)
      ? this.attributes[key]
      : null;
  };

  ElementNode.prototype.hasAttribute = function (name) {
    return Object.prototype.hasOwnProperty.call(this.attributes, String(name));
  };

  Object.defineProperty(ElementNode.prototype, "outerHTML", {
    get: function () {
      var attributes = Object.keys(this.attributes).map(function (name) {
        return " " + name + '="' + escapeAttribute(this.attributes[name]) + '"';
      }, this);
      var styles = Object.keys(this.style)
        .filter(function (name) {
          return this.style[name] != null && this.style[name] !== "";
        }, this)
        .map(function (name) {
          return (
            name.replace(/[A-Z]/g, function (letter) {
              return "-" + letter.toLowerCase();
            }) +
            ": " +
            this.style[name]
          );
        }, this);
      if (styles.length > 0 && !this.hasAttribute("style")) {
        attributes.push(' style="' + escapeAttribute(styles.join("; ")) + '"');
      }
      return (
        "<" +
        this.nodeName +
        attributes.join("") +
        ">" +
        this.childNodes
          .map(function (child) {
            return child.outerHTML;
          })
          .join("") +
        "</" +
        this.nodeName +
        ">"
      );
    },
  });

  this.document = {
    createElementNS: function (namespace, name) {
      return new ElementNode(name);
    },
    createElement: function (name) {
      var element = new ElementNode(name);
      element.getContext = function () {
        return null;
      };
      return element;
    },
    createTextNode: function (value) {
      return new TextNode(value);
    },
    getElementById: function () {
      return null;
    },
    querySelectorAll: function () {
      return [];
    },
  };
  this.window = this;
})();
