/*
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { AfterViewInit, Component, ElementRef, Inject, OnInit, ViewChild } from "@angular/core";
import { ActivatedRoute, Router } from "@angular/router";
import { BreadcrumbService } from "../../../../../libraries/ng2-breadcrumb/components/breadcrumbService";
import { AppConfig } from "../../../../../config/app.config";
import { FormService } from "../../../../services/form.service";
import { SnackbarService } from "../../../../services/snackbar.service";
import { DialogService } from "../../../../services/dialog.service";
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from "@angular/material";

export interface DialogData {
  rawTemplate: string;
  template: string;
}

@Component({
  selector: 'app-form',
  templateUrl: './form.component.html',
  styleUrls: ['./form.component.scss']
})
export class FormComponent implements OnInit, AfterViewInit {
  private domainId: string;
  private clientId: string;
  private defaultFormContent: string = `// Custom form...`;
  template: string;
  rawTemplate: string;
  form: any;
  formName: string;
  formContent: string = (' ' + this.defaultFormContent).slice(1);
  originalFormContent: string = (' ' + this.formContent).slice(1);
  formFound: boolean = false;
  formChanged: boolean = false;
  config: any = { lineNumbers: true, readOnly: true};
  @ViewChild('editor') editor: any;
  @ViewChild('preview') preview: ElementRef;

  constructor(private router: Router,
              private route: ActivatedRoute,
              private breadcrumbService: BreadcrumbService,
              private formService: FormService,
              private snackbarService: SnackbarService,
              private dialogService: DialogService,
              public dialog: MatDialog) { }

  ngOnInit() {
    this.domainId = this.route.snapshot.parent.parent.params['domainId'];
    this.clientId = this.route.snapshot.parent.params['clientId'];
    if (this.router.routerState.snapshot.url.startsWith('/settings')) {
      this.domainId = AppConfig.settings.authentication.domainId;
      this.rawTemplate = 'LOGIN';
    } else {
      this.rawTemplate = this.route.snapshot.queryParams['template'];
    }

    this.form = this.route.snapshot.data['form'];
    if (this.form && this.form.content) {
      this.formContent = this.form.content;
      this.originalFormContent = (' ' + this.formContent).slice(1);
      this.formFound = true;
    } else {
      this.form = {};
      this.form.template = this.rawTemplate
    }

    this.template = this.rawTemplate.toLowerCase().replace(/_/g, ' ');;
    this.formName = this.template.charAt(0).toUpperCase() + this.template.slice(1);
    this.initBreadcrumb();
  }

  ngAfterViewInit(): void {
    this.enableCodeMirror();
  }

  initBreadcrumb() {
    this.breadcrumbService.addFriendlyNameForRouteRegex('/domains/'+this.domainId+'/settings/forms/form*', this.template);
  }

  isEnabled() {
    return this.form && this.form.enabled;
  }

  enableForm(event) {
    this.form.enabled = event.checked;
    this.enableCodeMirror();
    this.formChanged = true;
  }

  onTabSelectedChanged(e) {
    if (e.index === 1) {
      this.refreshPreview();
    }
  }

  refreshPreview() {
    let doc =  this.preview.nativeElement.contentDocument || this.preview.nativeElement.contentWindow;
    doc.open();
    doc.write(this.formContent);
    doc.close();
  }

  onContentChanges(e) {
    if (e !== this.originalFormContent) {
      this.formChanged = true;
    }
  }

  resizeIframe() {
    this.preview.nativeElement.style.height = this.preview.nativeElement.contentWindow.document.body.scrollHeight + 'px';
  }

  create() {
    this.form['content'] = this.formContent;
    this.formService.create(this.domainId, this.clientId, this.form).subscribe(data => {
      this.snackbarService.open("Form created");
      this.formFound = true;
      this.form = data;
      this.formChanged = false;
    })
  }

  update() {
    this.form['content'] = this.formContent;
    this.formService.update(this.domainId, this.clientId, this.form.id, this.form).subscribe(data => {
      this.snackbarService.open("Form updated");
      this.formFound = true;
      this.form = data;
      this.formChanged = false;
    })
  }

  delete(event) {
    event.preventDefault();
    this.dialogService
      .confirm('Delete form', 'Are you sure you want to delete this form ?')
      .subscribe(res => {
        if (res) {
          this.formService.delete(this.domainId, this.clientId, this.form.id).subscribe(response => {
            this.snackbarService.open("Form deleted");
            this.form = {};
            this.form.template = this.route.snapshot.queryParams['template'];
            this.formContent =  (' ' + this.defaultFormContent).slice(1);
            this.originalFormContent = (' ' + this.formContent).slice(1);
            this.formFound = false;
            this.formChanged = false;
            this.enableCodeMirror();
          });
        }
      });
  }

  openDialog() {
    this.dialog.open(FormInfoDialog, {
      data: {rawTemplate: this.rawTemplate, template: this.template}
    });
  }

  private enableCodeMirror(): void {
    this.editor.instance.setOption('readOnly', !this.form.enabled);
  }
}

@Component({
  selector: 'form-info-dialog',
  templateUrl: './dialog/form-info.component.html',
})
export class FormInfoDialog {
  constructor(public dialogRef: MatDialogRef<FormInfoDialog>, @Inject(MAT_DIALOG_DATA) public data: DialogData) {}
}
