/**
 * @license
 * Copyright (C) 2020 The Android Open Source Project
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
import {Finalizable} from './registry';
import {FlagsService} from './flags/flags';
import {EventEmitterService} from './gr-event-interface/gr-event-interface';
import {ReportingService} from './gr-reporting/gr-reporting';
import {AuthService} from './gr-auth/gr-auth';
import {RestApiService} from './gr-rest-api/gr-rest-api';
import {ChangeService} from './change/change-service';
import {ChecksService} from './checks/checks-service';
import {JsApiService} from '../elements/shared/gr-js-api-interface/gr-js-api-types';
import {StorageService} from './storage/gr-storage';
import {UserModel} from './user/user-model';
import {CommentsService} from './comments/comments-service';
import {ShortcutsService} from './shortcuts/shortcuts-service';
import {BrowserModel} from './browser/browser-model';
import {ConfigModel} from './config/config-model';

export interface AppContext {
  flagsService: FlagsService;
  reportingService: ReportingService;
  eventEmitter: EventEmitterService;
  authService: AuthService;
  restApiService: RestApiService;
  changeService: ChangeService;
  commentsService: CommentsService;
  checksService: ChecksService;
  jsApiService: JsApiService;
  storageService: StorageService;
  configModel: ConfigModel;
  userModel: UserModel;
  browserModel: BrowserModel;
  shortcutsService: ShortcutsService;
}

/**
 * The AppContext holds instances of services. It's a convenient way to provide
 * singletons that can be swapped out for testing.
 *
 * AppContext is initialized in ./app-context-init.js
 *
 * It is guaranteed that all fields in appContext are always initialized
 * (except for shared gr-diff)
 */
let appContext: (AppContext & Finalizable) | undefined = undefined;

export function injectAppContext(ctx: AppContext & Finalizable) {
  appContext?.finalize();
  appContext = ctx;
}

export function getAppContext() {
  if (!appContext) throw new Error('App context has not been injected');
  return appContext;
}
