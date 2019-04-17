/**
 * @license
 * Copyright (C) 2016 The Android Open Source Project
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
(function() {
  'use strict';

  Polymer({
    is: 'gr-group-list',
    _legacyUndefinedCheck: true,

    properties: {
      _groups: Array,
    },

    loadData() {
      return this.$.restAPI.getAccountGroups().then(groups => {
        this._groups = groups.sort((a, b) => {
          return a.name.localeCompare(b.name);
        });
      });
    },

    _computeVisibleToAll(group) {
      return group.options.visible_to_all ? 'Yes' : 'No';
    },

    _computeGroupPath(group) {
      if (!group || !group.id) { return; }

      return Gerrit.Nav.getUrlForGroup(group.id);
    },
  });
})();
