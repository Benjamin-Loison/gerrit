/**
 * @license
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
(function(window) {
  'use strict';

  function GrDomHooksManager(plugin) {
    this._plugin = plugin;
    this._hooks = {};
  }

  GrDomHooksManager.prototype._getHookName = function(endpointName,
      opt_moduleName) {
    if (opt_moduleName) {
      return endpointName + ' ' + opt_moduleName;
    } else {
      return this._plugin.getPluginName() + '-autogenerated-' + endpointName;
    }
  };

  GrDomHooksManager.prototype.getDomHook = function(endpointName,
      opt_moduleName) {
    const hookName = this._getHookName(endpointName, opt_moduleName);
    if (!this._hooks[hookName]) {
      this._hooks[hookName] = new GrDomHook(hookName, opt_moduleName);
    }
    return this._hooks[hookName];
  };

  function GrDomHook(hookName, opt_moduleName) {
    this._instances = [];
    this._callbacks = [];
    if (opt_moduleName) {
      this._moduleName = opt_moduleName;
    } else {
      this._moduleName = hookName;
      this._createPlaceholder(hookName);
    }
  }

  GrDomHook.prototype._createPlaceholder = function(hookName) {
    Polymer({
      is: hookName,
      _legacyUndefinedCheck: true,
      properties: {
        plugin: Object,
        content: Object,
      },
    });
  };

  GrDomHook.prototype.handleInstanceDetached = function(instance) {
    const index = this._instances.indexOf(instance);
    if (index !== -1) {
      this._instances.splice(index, 1);
    }
  };

  GrDomHook.prototype.handleInstanceAttached = function(instance) {
    this._instances.push(instance);
    this._callbacks.forEach(callback => callback(instance));
  };

  /**
   * Get instance of last DOM hook element attached into the endpoint.
   * Returns a Promise, that's resolved when attachment is done.
   * @return {!Promise<!Element>}
   */
  GrDomHook.prototype.getLastAttached = function() {
    if (this._instances.length) {
      return Promise.resolve(this._instances.slice(-1)[0]);
    }
    if (!this._lastAttachedPromise) {
      let resolve;
      const promise = new Promise(r => resolve = r);
      this._callbacks.push(resolve);
      this._lastAttachedPromise = promise.then(element => {
        this._lastAttachedPromise = null;
        const index = this._callbacks.indexOf(resolve);
        if (index !== -1) {
          this._callbacks.splice(index, 1);
        }
        return element;
      });
    }
    return this._lastAttachedPromise;
  };

  /**
   * Get all DOM hook elements.
   */
  GrDomHook.prototype.getAllAttached = function() {
    return this._instances;
  };

  /**
   * Install a new callback to invoke when a new instance of DOM hook element
   * is attached.
   * @param {function(Element)} callback
   */
  GrDomHook.prototype.onAttached = function(callback) {
    this._callbacks.push(callback);
    return this;
  };

  /**
   * Name of DOM hook element that will be installed into the endpoint.
   */
  GrDomHook.prototype.getModuleName = function() {
    return this._moduleName;
  };

  GrDomHook.prototype.getPublicAPI = function() {
    const result = {};
    const exposedMethods = [
      'onAttached', 'getLastAttached', 'getAllAttached', 'getModuleName',
    ];
    for (const p of exposedMethods) {
      result[p] = this[p].bind(this);
    }
    return result;
  };

  window.GrDomHook = GrDomHook;
  window.GrDomHooksManager = GrDomHooksManager;
})(window);
